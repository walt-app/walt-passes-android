package `is`.walt.passes.core.internal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import `is`.walt.passes.core.ParserConfig
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.core.TamperReason
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.cms.SignedData
import org.bouncycastle.asn1.cms.SignerInfo
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.junit.BeforeClass
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import kotlin.random.Random

/**
 * Locks the contract that [verifySignature] never lets an exception escape: trust-anchor
 * loader faults and a corpus of malformed CMS envelopes, all built in memory, must each
 * come back as [SignatureVerifyResult.Failed].
 */
class SignatureVerifierNeverThrowsTest {
    @Test
    fun trustLoaderFaultMapsToCryptoFailure() {
        val chain = signedChain()
        val faults = listOf(IllegalStateException("cert missing"), CertificateException("corrupt cert"))
        for (fault in faults) {
            val result =
                verifySignatureWithTrustLoaderForTesting(chain.signature, chain.manifest, ParserConfig()) {
                    throw fault
                }
            assertWithMessage(fault.toString()).that(result).isEqualTo(CRYPTO_FAILURE)
        }
    }

    @Test
    fun trustLoaderFaultIsNotStickyOnceTheLoaderRecovers() {
        // Locks that the verifier keeps no state between calls: a loader fault on one call
        // does not change the verdict of the next.
        val chain = signedChain()
        var calls = 0
        val loader = {
            calls++
            check(calls > 1) { "first load fails" }
            setOf(chain.anchor) to emptySet<X509Certificate>()
        }

        val first = verifySignatureWithTrustLoaderForTesting(chain.signature, chain.manifest, ParserConfig(), loader)
        val second = verifySignatureWithTrustLoaderForTesting(chain.signature, chain.manifest, ParserConfig(), loader)

        assertThat(first).isEqualTo(CRYPTO_FAILURE)
        assertThat(second).isEqualTo(SignatureVerifyResult.Ok(SignatureStatus.AppleVerified))
    }

    @Test
    fun malformedEnvelopesFailWithTheirPinnedReason() {
        val valid = selfSignedEnvelope()
        val corpus = byteLevelCorpus(valid) + structuralCorpus(ContentInfo.getInstance(valid))

        val actual = corpus.mapValues { (_, case) -> verify(case.first) }
        val expected = corpus.mapValues { (_, case) -> SignatureVerifyResult.Failed(case.second) }
        assertThat(actual).containsExactlyEntriesIn(expected)
    }

    @Test
    fun everyTruncationIsRejected() {
        val valid = selfSignedEnvelope()
        for (length in 0 until valid.size) {
            assertRejected("truncated to $length bytes", valid.copyOf(length))
        }
    }

    @Test
    fun everySingleByteCorruptionFailsWithoutThrowingUnderStrict() {
        // Strict fails any chain short of Apple, so this proves only that nothing escapes;
        // lenient would report a flip inside the cert as Ok(CertChainIncomplete).
        val valid = selfSignedEnvelope()
        for (index in valid.indices) {
            val mutated = valid.copyOf().also { it[index] = (it[index].toInt() xor 0xFF).toByte() }
            assertRejected("byte $index flipped", mutated, ParserConfig.Strict)
        }
    }

    private fun assertRejected(
        label: String,
        blob: ByteArray,
        config: ParserConfig = ParserConfig(),
    ) {
        assertWithMessage(label).that(verify(blob, config)).isInstanceOf(SignatureVerifyResult.Failed::class.java)
    }

    private fun verify(
        blob: ByteArray,
        config: ParserConfig = ParserConfig(),
    ): SignatureVerifyResult = verifySignature(blob, MANIFEST, config)

    private class SignedChain(
        val signature: ByteArray,
        val manifest: ByteArray,
        val anchor: TrustAnchor,
    )

    private fun signedChain(): SignedChain {
        val rootKey = newRsaKeyPair()
        val root = selfSignedCertificate(rootKey, "CN=Test Root")
        val leafKey = newRsaKeyPair()
        val leaf = signLeafCertificate(leafKey.public, rootKey, root, "CN=Test Leaf")
        val signature = cmsDetachedSignature(MANIFEST, leaf, leafKey.private, listOf(leaf, root))
        return SignedChain(signature, MANIFEST, TrustAnchor(root, null))
    }

    private fun selfSignedEnvelope(): ByteArray {
        val key = newRsaKeyPair()
        val leaf = selfSignedCertificate(key, "CN=Self Signed Leaf")
        // DER, like real pkpass signatures; BouncyCastle emits indefinite-length BER.
        val ber = cmsDetachedSignature(MANIFEST, leaf, key.private, listOf(leaf))
        return der(ContentInfo.getInstance(ber)).also {
            check(verify(it) == SignatureVerifyResult.Ok(SignatureStatus.SelfSigned))
        }
    }

    private fun byteLevelCorpus(valid: ByteArray): Map<String, Pair<ByteArray, TamperReason>> =
        mapOf(
            "lone SEQUENCE tag" to (byteArrayOf(0x30) to CRYPTO),
            "indefinite header, no content" to (bytes(0x30, 0x80) to CRYPTO),
            "indefinite empty SEQUENCE" to (bytes(0x30, 0x80, 0x00, 0x00) to CRYPTO),
            "outer length near Int.MAX_VALUE" to
                (withOuterLength(valid, bytes(0x84, 0x7F, 0xFF, 0xFF, 0xFF)) to CRYPTO),
            "nine-byte length field" to (bytes(0x30, 0x89) + ByteArray(9) { 0xFF.toByte() } to CRYPTO),
            "nesting past BouncyCastle's 32-level limit" to (nestedIndefinite(depth = 64) to CRYPTO),
            "empty SignedData SEQUENCE" to (cmsOf(DERSequence()) to CRYPTO),
            "SignedData is an OCTET STRING of noise" to (cmsOf(DEROctetString(noise(256))) to CRYPTO),
            "SignedData holds nested noise" to
                (cmsOf(DERSequence(arrayOf(DERSequence(DEROctetString(noise(64))), ASN1Integer(7)))) to CRYPTO),
        )

    private fun structuralCorpus(contentInfo: ContentInfo): Map<String, Pair<ByteArray, TamperReason>> {
        val sd = SignedData.getInstance(contentInfo.content)
        val signer = SignerInfo.getInstance(sd.signerInfos.getObjectAt(0))

        fun envelope(
            encap: ContentInfo = sd.encapContentInfo,
            certs: ASN1Set? = sd.certificates,
            signers: ASN1Set = sd.signerInfos,
        ): ByteArray = cmsOf(SignedData(sd.digestAlgorithms, encap, certs, null, signers))

        fun withSigner(changed: SignerInfo): ByteArray = envelope(signers = DERSet(changed))

        return mapOf(
            "encapsulated content of unknown type" to
                (envelope(encap = ContentInfo(BOGUS_ALG.algorithm, null)) to CRYPTO),
            "no signers, no certs" to (envelope(certs = DERSet(), signers = DERSet()) to MISSING_CERT),
            "signer without certs" to (envelope(certs = null) to MISSING_CERT),
            "unknown digest algorithm" to (withSigner(signer.copy(digestAlg = BOGUS_ALG)) to CRYPTO),
            "unknown signature algorithm" to (withSigner(signer.copy(sigAlg = BOGUS_ALG)) to CRYPTO),
            "signed attributes hold a non-attribute" to
                (withSigner(signer.copy(signedAttrs = DERSet(ASN1Integer(1)))) to CRYPTO),
            "empty signature value" to
                (withSigner(signer.copy(sig = DEROctetString(ByteArray(0)))) to TamperReason.ManifestSignatureMismatch),
        )
    }

    private fun cmsOf(content: ASN1Encodable): ByteArray = der(ContentInfo(CMSObjectIdentifiers.signedData, content))

    private fun SignerInfo.copy(
        digestAlg: AlgorithmIdentifier = digestAlgorithm,
        signedAttrs: ASN1Set? = authenticatedAttributes,
        sigAlg: AlgorithmIdentifier = digestEncryptionAlgorithm,
        sig: DEROctetString = DEROctetString(encryptedDigest.octets),
    ): SignerInfo = SignerInfo(sid, digestAlg, signedAttrs, sigAlg, sig, unauthenticatedAttributes)

    private fun withOuterLength(
        valid: ByteArray,
        lengthField: ByteArray,
    ): ByteArray {
        // The synthesized envelope is always over 255 bytes, so its header is 30 82 xx xx.
        check(valid[1] == 0x82.toByte())
        return byteArrayOf(0x30) + lengthField + valid.copyOfRange(4, valid.size)
    }

    private fun nestedIndefinite(depth: Int): ByteArray =
        ByteArray(depth * 2) { if (it % 2 == 0) 0x30 else 0x80.toByte() } + ByteArray(depth * 2)

    private fun noise(size: Int): ByteArray = Random(seed = size).nextBytes(size)

    private fun der(value: ASN1Encodable): ByteArray = value.toASN1Primitive().getEncoded(ASN1Encoding.DER)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    companion object {
        private val MANIFEST = "{\"pass.json\":\"abcd\"}".toByteArray()
        private val CRYPTO = TamperReason.SignatureCryptoFailure
        private val MISSING_CERT = TamperReason.SignerCertificateMissing
        private val CRYPTO_FAILURE = SignatureVerifyResult.Failed(CRYPTO)
        private val BOGUS_ALG = AlgorithmIdentifier(ASN1ObjectIdentifier("1.3.6.1.4.1.99999.1"))

        @BeforeClass
        @JvmStatic
        fun installBouncyCastle() {
            ensureBouncyCastleProvider()
        }
    }
}
