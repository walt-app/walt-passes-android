package `is`.walt.passes.core.internal

import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.ParserConfig
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.core.TamperReason
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.junit.BeforeClass
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Behavior tests for [verifySignature]. Every fixture is built in-memory at test
 * time so a reviewer can read each test top-to-bottom and see exactly which arm of
 * [SignatureVerifyResult] is being exercised. The test "Apple" trust anchor is a
 * synthesized CA — Apple's real WWDR private key is unavailable, so the production
 * `verifySignature(sigBytes, manifestBytes, config)` entrypoint is exercised
 * indirectly through [verifySignatureAgainst], which lets the test substitute its
 * own anchor for the bundled set.
 *
 * The wider "the bundled Apple anchors load and chain shape" assertion lives in
 * [AppleTrustAnchorsTest]; together the two suites cover both sides without
 * needing Apple's signing key.
 */
class SignatureVerifierTest {
    @Test
    fun appleVerifiedWhenChainTerminatesAtBundledRoot() {
        val rootKey = newRsaKeyPair()
        val rootCert = selfSign(rootKey, "CN=Test Apple Root")
        val leafKey = newRsaKeyPair()
        val leafCert = signLeafWith(leafKey.public, rootKey, rootCert, "CN=Test Apple Leaf")
        val manifest = "{\"pass.json\":\"abcd\"}".toByteArray()
        val signature = sign(manifest, leafCert, leafKey.private, listOf(leafCert, rootCert))

        val result =
            verifySignatureAgainst(
                signatureBytes = signature,
                manifestBytes = manifest,
                config = ParserConfig(),
                trustAnchors = setOf(TrustAnchor(rootCert, null)),
                knownIntermediates = emptySet(),
            )

        assertOk(result, SignatureStatus.AppleVerified)
    }

    @Test
    fun selfSignedLeafLeniently() {
        val key = newRsaKeyPair()
        val leaf = selfSign(key, "CN=Self Signed Leaf")
        val manifest = "{}".toByteArray()
        // The signature blob carries only the self-signed leaf; no intermediates.
        val signature = sign(manifest, leaf, key.private, listOf(leaf))

        val result = runWithFakeAnchor(signature, manifest, ParserConfig())

        assertOk(result, SignatureStatus.SelfSigned)
    }

    @Test
    fun certChainIncompleteWhenIntermediatePresentButNoKnownRoot() {
        // Two-level chain that does NOT terminate at our test anchor — the test
        // anchor here is some unrelated CA. The chain extends (leaf + intermediate
        // signed by an out-of-band root) but does not reach the trusted set.
        val unrelatedKey = newRsaKeyPair()
        val unrelatedAnchor = selfSign(unrelatedKey, "CN=Unrelated Anchor")
        val intermediateKey = newRsaKeyPair()
        val intermediate = selfSign(intermediateKey, "CN=Stranger Intermediate")
        val leafKey = newRsaKeyPair()
        val leaf = signLeafWith(leafKey.public, intermediateKey, intermediate, "CN=Stranger Leaf")
        val manifest = "{\"k\":\"v\"}".toByteArray()
        val signature = sign(manifest, leaf, leafKey.private, listOf(leaf, intermediate))

        val result =
            verifySignatureAgainst(
                signatureBytes = signature,
                manifestBytes = manifest,
                config = ParserConfig(),
                trustAnchors = setOf(TrustAnchor(unrelatedAnchor, null)),
                knownIntermediates = emptySet(),
            )

        assertOk(result, SignatureStatus.CertChainIncomplete)
    }

    @Test
    fun strictRejectsSelfSigned() {
        val key = newRsaKeyPair()
        val leaf = selfSign(key, "CN=Self Signed Leaf")
        val manifest = "{}".toByteArray()
        val signature = sign(manifest, leaf, key.private, listOf(leaf))

        val result = runWithFakeAnchor(signature, manifest, ParserConfig.Strict)

        assertFailed(result, TamperReason.SignatureCryptoFailure)
    }

    @Test
    fun strictRejectsCertChainIncomplete() {
        val unrelatedKey = newRsaKeyPair()
        val unrelatedAnchor = selfSign(unrelatedKey, "CN=Unrelated Anchor")
        val intermediateKey = newRsaKeyPair()
        val intermediate = selfSign(intermediateKey, "CN=Intermediate")
        val leafKey = newRsaKeyPair()
        val leaf = signLeafWith(leafKey.public, intermediateKey, intermediate, "CN=Leaf")
        val manifest = "{}".toByteArray()
        val signature = sign(manifest, leaf, leafKey.private, listOf(leaf, intermediate))

        val result =
            verifySignatureAgainst(
                signatureBytes = signature,
                manifestBytes = manifest,
                config = ParserConfig.Strict,
                trustAnchors = setOf(TrustAnchor(unrelatedAnchor, null)),
                knownIntermediates = emptySet(),
            )

        assertFailed(result, TamperReason.SignatureCryptoFailure)
    }

    @Test
    fun manifestSignatureMismatchWhenManifestBytesTamperedByOneByte() {
        val key = newRsaKeyPair()
        val leaf = selfSign(key, "CN=Self")
        val original = "{\"pass.json\":\"abcd\"}".toByteArray()
        val signature = sign(original, leaf, key.private, listOf(leaf))
        // Mutate exactly one byte.
        val tampered = original.copyOf().also { it[1] = 0x20 }

        val result = runWithFakeAnchor(signature, tampered, ParserConfig())

        assertFailed(result, TamperReason.ManifestSignatureMismatch)
    }

    @Test
    fun signatureCryptoFailureForGarbageBlob() {
        val garbage = ByteArray(64) { it.toByte() }
        val manifest = "{}".toByteArray()

        val result = runWithFakeAnchor(garbage, manifest, ParserConfig())

        assertFailed(result, TamperReason.SignatureCryptoFailure)
    }

    @Test
    fun signatureCryptoFailureForEmptyBlob() {
        val result = runWithFakeAnchor(ByteArray(0), "{}".toByteArray(), ParserConfig())
        assertFailed(result, TamperReason.SignatureCryptoFailure)
    }

    @Test
    fun productionEntrypointRoutesGarbageToCryptoFailure() {
        // Exercises the `verifySignature(sig, manifest, config)` entrypoint that
        // loads bundled Apple anchors. Garbage input never depends on which anchors
        // are loaded, so this is the only assertion safe to make against the
        // production entrypoint without Apple's private key.
        val result =
            verifySignature(
                signatureBytes = ByteArray(8),
                manifestBytes = "{}".toByteArray(),
                config = ParserConfig(),
            )
        assertFailed(result, TamperReason.SignatureCryptoFailure)
    }

    @Test
    fun appleVerifiedBeatsLeafThatIsAlsoSelfSigned() {
        // Self-issued root and a leaf signed by it. The leaf is NOT self-signed
        // (issuer=root, subject=leaf), but a misclassifier that checked self-
        // signedness before trying chain validation would surface this as
        // SelfSigned. The classifier must try the anchor first.
        val rootKey = newRsaKeyPair()
        val root = selfSign(rootKey, "CN=Test Apple Root")
        val leafKey = newRsaKeyPair()
        val leaf = signLeafWith(leafKey.public, rootKey, root, "CN=Apple Leaf")
        val manifest = "x".toByteArray()
        val signature = sign(manifest, leaf, leafKey.private, listOf(leaf, root))

        val result =
            verifySignatureAgainst(
                signature,
                manifest,
                ParserConfig(),
                setOf(TrustAnchor(root, null)),
                emptySet(),
            )
        assertOk(result, SignatureStatus.AppleVerified)
    }

    @Test
    fun knownIntermediateLetsBlobOmitItAndStillReachAnchor() {
        // The signature blob includes only the leaf, not the intermediate. The
        // bundled `knownIntermediates` set fills the gap so the chain still
        // resolves to the trust anchor. This exercises the WWDR-G6-bundled path.
        val rootKey = newRsaKeyPair()
        val root = selfSign(rootKey, "CN=Test Apple Root")
        val intermediateKey = newRsaKeyPair()
        val intermediate = signLeafWith(intermediateKey.public, rootKey, root, "CN=Test WWDR", isCa = true)
        val leafKey = newRsaKeyPair()
        val leaf = signLeafWith(leafKey.public, intermediateKey, intermediate, "CN=Test Apple Leaf")
        val manifest = "{\"k\":\"v\"}".toByteArray()
        val signature = sign(manifest, leaf, leafKey.private, listOf(leaf))

        val result =
            verifySignatureAgainst(
                signature,
                manifest,
                ParserConfig(),
                setOf(TrustAnchor(root, null)),
                setOf(intermediate),
            )

        assertOk(result, SignatureStatus.AppleVerified)
    }

    private fun runWithFakeAnchor(
        signature: ByteArray,
        manifest: ByteArray,
        config: ParserConfig,
    ): SignatureVerifyResult {
        val anchorKey = newRsaKeyPair()
        val anchor = selfSign(anchorKey, "CN=Unrelated Anchor")
        return verifySignatureAgainst(
            signature,
            manifest,
            config,
            setOf(TrustAnchor(anchor, null)),
            emptySet(),
        )
    }

    private fun assertOk(
        result: SignatureVerifyResult,
        expected: SignatureStatus,
    ) {
        assertThat(result).isInstanceOf(SignatureVerifyResult.Ok::class.java)
        Truth.assertWithMessage("expected Ok($expected)")
            .that((result as SignatureVerifyResult.Ok).status)
            .isEqualTo(expected)
    }

    private fun assertFailed(
        result: SignatureVerifyResult,
        expected: TamperReason,
    ) {
        assertThat(result).isInstanceOf(SignatureVerifyResult.Failed::class.java)
        Truth.assertWithMessage("expected Failed($expected)")
            .that((result as SignatureVerifyResult.Failed).reason)
            .isEqualTo(expected)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun installBouncyCastle() {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }
}

private fun newRsaKeyPair(): KeyPair {
    val gen = KeyPairGenerator.getInstance("RSA", "BC")
    gen.initialize(RSA_KEY_BITS)
    return gen.generateKeyPair()
}

private fun selfSign(
    keys: KeyPair,
    subjectDn: String,
): X509Certificate {
    val now = System.currentTimeMillis()
    val notBefore = Date(now - ONE_HOUR_MILLIS)
    val notAfter = Date(now + ONE_YEAR_MILLIS)
    val subject = X500Name(subjectDn)
    val builder: X509v3CertificateBuilder =
        JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            notBefore,
            notAfter,
            subject,
            keys.public,
        )
    val signer = JcaContentSignerBuilder(SIG_ALGO).setProvider("BC").build(keys.private)
    val holder = builder.build(signer)
    return JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)
}

private fun signLeafWith(
    leafPublicKey: PublicKey,
    issuerKey: KeyPair,
    issuerCert: X509Certificate,
    subjectDn: String,
    isCa: Boolean = false,
): X509Certificate {
    val now = System.currentTimeMillis()
    val builder: X509v3CertificateBuilder =
        JcaX509v3CertificateBuilder(
            X500Name(issuerCert.subjectX500Principal.name),
            BigInteger.valueOf(now + 1),
            Date(now - ONE_HOUR_MILLIS),
            Date(now + ONE_YEAR_MILLIS),
            X500Name(subjectDn),
            leafPublicKey,
        )
    if (isCa) {
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(0))
    }
    val signer = JcaContentSignerBuilder(SIG_ALGO).setProvider("BC").build(issuerKey.private)
    val holder = builder.build(signer)
    return JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)
}

private fun sign(
    content: ByteArray,
    signerCert: X509Certificate,
    signerKey: PrivateKey,
    includedCerts: List<X509Certificate>,
): ByteArray {
    val gen = CMSSignedDataGenerator()
    val contentSigner = JcaContentSignerBuilder(SIG_ALGO).setProvider("BC").build(signerKey)
    val infoGen =
        JcaSignerInfoGeneratorBuilder(
            JcaDigestCalculatorProviderBuilder().setProvider("BC").build(),
        ).build(contentSigner, signerCert)
    gen.addSignerInfoGenerator(infoGen)
    gen.addCertificates(JcaCertStore(includedCerts))
    val signed = gen.generate(CMSProcessableByteArray(content), false)
    return signed.encoded
}

private const val SIG_ALGO = "SHA256withRSA"
private const val RSA_KEY_BITS = 2048
private const val ONE_HOUR_MILLIS = 60L * 60L * 1000L
private const val ONE_YEAR_MILLIS = 365L * 24L * ONE_HOUR_MILLIS
