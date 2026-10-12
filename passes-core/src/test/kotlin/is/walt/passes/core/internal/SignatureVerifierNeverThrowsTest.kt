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
                verifySignatureWithTrustLoader(chain.signature, chain.manifest, ParserConfig()) {
                    throw fault
                }
            assertWithMessage(fault.toString()).that(result).isEqualTo(CRYPTO_FAILURE)
        }
    }

    @Test
    fun trustLoaderFaultIsNotStickyOnceTheLoaderRecovers() {
        // A failing `by lazy` initializer retries on the next call; the verifier must not
        // remember the earlier fault either.
        val chain = signedChain()
        var calls = 0
        val loader = {
            calls++
            check(calls > 1) { "first load fails" }
            setOf(chain.anchor) to emptySet<X509Certificate>()
        }

        val first = verifySignatureWithTrustLoader(chain.signature, chain.manifest, ParserConfig(), loader)
        val second = verifySignatureWithTrustLoader(chain.signature, chain.manifest, ParserConfig(), loader)

        assertThat(first).isEqualTo(CRYPTO_FAILURE)
        assertThat(second).isEqualTo(SignatureVerifyResult.Ok(SignatureStatus.AppleVerified))
    }

    @Test
    fun malformedEnvelopesAreRejected() {
        val valid = selfSignedEnvelope()
        val contentInfo = ContentInfo.getInstance(valid)
        val signedData = SignedData.getInstance(contentInfo.content)
        val signer = SignerInfo.getInstance(signedData.signerInfos.getObjectAt(0))
        val bogusAlg = AlgorithmIdentifier(ASN1ObjectIdentifier("1.3.6.1.4.1.99999.1"))
        val corpus =
            mapOf(
                "lone SEQUENCE tag" to byteArrayOf(0x30),
                "indefinite header, no content" to bytes(0x30, 0x80),
                "indefinite empty SEQUENCE" to bytes(0x30, 0x80, 0x00, 0x00),
                "outer length near Int.MAX_VALUE" to withOuterLength(valid, bytes(0x84, 0x7F, 0xFF, 0xFF, 0xFF)),
                "nine-byte length field" to bytes(0x30, 0x89) + ByteArray(9) { 0xFF.toByte() },
                "encapsulated content of unknown type" to
                    withSignedData(
                        contentInfo,
                        SignedData(
                            signedData.digestAlgorithms,
                            ContentInfo(bogusAlg.algorithm, null),
                            signedData.certificates,
                            null,
                            signedData.signerInfos,
                        ),
                    ),
                "empty SignedData SEQUENCE" to der(ContentInfo(CMSObjectIdentifiers.signedData, DERSequence())),
                "SignedData is an OCTET STRING of noise" to
                    der(ContentInfo(CMSObjectIdentifiers.signedData, DEROctetString(noise(256)))),
                "SignedData holds nested noise" to
                    der(
                        ContentInfo(
                            CMSObjectIdentifiers.signedData,
                            DERSequence(arrayOf(DERSequence(DEROctetString(noise(64))), ASN1Integer(7))),
                        ),
                    ),
                "deeply nested indefinite SEQUENCEs" to nestedIndefinite(depth = 4096),
                "unknown digest algorithm" to
                    withSigner(contentInfo, signedData, signer.copy(digestAlg = bogusAlg)),
                "unknown signature algorithm" to
                    withSigner(contentInfo, signedData, signer.copy(sigAlg = bogusAlg)),
                "signed attributes hold a non-attribute" to
                    withSigner(contentInfo, signedData, signer.copy(signedAttrs = DERSet(ASN1Integer(1)))),
                "empty signature value" to
                    withSigner(contentInfo, signedData, signer.copy(sig = DEROctetString(ByteArray(0)))),
            )
        for ((label, blob) in corpus) {
            assertRejected(label, blob)
        }
    }

    @Test
    fun envelopesWithNoSignerOrNoCertificatesReportTheMissingCert() {
        val contentInfo = ContentInfo.getInstance(selfSignedEnvelope())
        val signedData = SignedData.getInstance(contentInfo.content)
        val noSigners =
            SignedData(signedData.digestAlgorithms, signedData.encapContentInfo, DERSet(), null, DERSet())
        val noCerts =
            SignedData(signedData.digestAlgorithms, signedData.encapContentInfo, null, null, signedData.signerInfos)

        for ((label, sd) in mapOf("no signers, no certs" to noSigners, "signer without certs" to noCerts)) {
            val result = verify(withSignedData(contentInfo, sd))
            assertWithMessage(label)
                .that(result)
                .isEqualTo(SignatureVerifyResult.Failed(TamperReason.SignerCertificateMissing))
        }
    }

    @Test
    fun everyTruncationIsRejected() {
        val valid = selfSignedEnvelope()
        for (length in 0 until valid.size) {
            assertRejected("truncated to $length bytes", valid.copyOf(length))
        }
    }

    @Test
    fun everySingleByteCorruptionIsRejectedUnderStrict() {
        // Strict, because a flip inside the embedded cert only breaks its chain, which the
        // lenient default reports as Ok(CertChainIncomplete).
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

    private fun SignerInfo.copy(
        digestAlg: AlgorithmIdentifier = digestAlgorithm,
        signedAttrs: ASN1Set? = authenticatedAttributes,
        sigAlg: AlgorithmIdentifier = digestEncryptionAlgorithm,
        sig: DEROctetString = DEROctetString(encryptedDigest.octets),
    ): SignerInfo = SignerInfo(sid, digestAlg, signedAttrs, sigAlg, sig, unauthenticatedAttributes)

    private fun withSigner(
        contentInfo: ContentInfo,
        signedData: SignedData,
        signer: SignerInfo,
    ): ByteArray =
        withSignedData(
            contentInfo,
            SignedData(
                signedData.digestAlgorithms,
                signedData.encapContentInfo,
                signedData.certificates,
                signedData.crLs,
                DERSet(signer),
            ),
        )

    private fun withSignedData(
        contentInfo: ContentInfo,
        signedData: SignedData,
    ): ByteArray = der(ContentInfo(contentInfo.contentType, signedData))

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
        private val CRYPTO_FAILURE = SignatureVerifyResult.Failed(TamperReason.SignatureCryptoFailure)

        @BeforeClass
        @JvmStatic
        fun installBouncyCastle() {
            ensureBouncyCastleProvider()
        }
    }
}
