package `is`.walt.passes.core.internal

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Shared test helpers for synthesizing X.509 certificates and CMS / PKCS#7 signature
 * blobs. Lives here (rather than in two near-identical private blocks at the bottom of
 * [SignatureVerifierTest] and [SyntheticPkpass]) so that the two suites stay
 * structurally identical: a change to the signing math has exactly one site to update,
 * and a regression in one suite is informative about the same path in the other.
 *
 * Visibility is `internal` to the module — these helpers are test-only because they
 * generate ephemeral RSA keys, but Kotlin's test source set lives in the same module
 * as production code, so any test class in `passes-core` can call them.
 */
internal fun ensureBouncyCastleProvider() {
    if (Security.getProvider(BC_PROVIDER) == null) {
        Security.addProvider(BouncyCastleProvider())
    }
}

internal fun newRsaKeyPair(): KeyPair {
    ensureBouncyCastleProvider()
    val gen = KeyPairGenerator.getInstance("RSA", BC_PROVIDER)
    gen.initialize(RSA_KEY_BITS)
    return gen.generateKeyPair()
}

/**
 * Build a self-signed certificate. Subject and issuer are the same DN; the certificate
 * verifies under its own public key. Used directly as a self-signed leaf, and as a
 * synthesized "Apple-like" root anchor in tests that exercise the chain-builder path.
 */
internal fun selfSignedCertificate(
    keyPair: KeyPair,
    subjectDn: String,
): X509Certificate {
    ensureBouncyCastleProvider()
    val now = System.currentTimeMillis()
    val subject = X500Name(subjectDn)
    val builder =
        JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            Date(now - ONE_HOUR_MILLIS),
            Date(now + ONE_YEAR_MILLIS),
            subject,
            keyPair.public,
        )
    builder.addSubjectKeyIdentifier(keyPair.public)
    val signer = JcaContentSignerBuilder(SIG_ALGO).setProvider(BC_PROVIDER).build(keyPair.private)
    return JcaX509CertificateConverter()
        .setProvider(BC_PROVIDER)
        .getCertificate(builder.build(signer))
}

/**
 * Build a certificate for [leafPublicKey] signed by [issuerKey], anchored to the issuer
 * DN from [issuerCert]. When [isCa] is true the certificate carries a CA basic
 * constraint with `pathLen=0`, which lets it function as an intermediate that may sign
 * one further-down leaf — sufficient for synthesizing the WWDR-style intermediate
 * tested in [SignatureVerifierTest.knownIntermediateLetsBlobOmitItAndStillReachAnchor].
 */
internal fun signLeafCertificate(
    leafPublicKey: PublicKey,
    issuerKey: KeyPair,
    issuerCert: X509Certificate,
    subjectDn: String,
    isCa: Boolean = false,
): X509Certificate {
    ensureBouncyCastleProvider()
    val now = System.currentTimeMillis()
    val builder =
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
    builder.addSubjectKeyIdentifier(leafPublicKey)
    val signer = JcaContentSignerBuilder(SIG_ALGO).setProvider(BC_PROVIDER).build(issuerKey.private)
    return JcaX509CertificateConverter()
        .setProvider(BC_PROVIDER)
        .getCertificate(builder.build(signer))
}

/**
 * Produce a detached CMS / PKCS#7 signature blob over [content], signed by [signerKey]
 * (whose certificate is [signerCert]). [includedCerts] are embedded in the signature
 * envelope's certificate set — pass the leaf alone for a self-signed-only blob, or
 * leaf + intermediate(s) when the chain should travel with the signature.
 */
internal fun cmsDetachedSignature(
    content: ByteArray,
    signerCert: X509Certificate,
    signerKey: PrivateKey,
    includedCerts: List<X509Certificate>,
): ByteArray {
    ensureBouncyCastleProvider()
    val contentSigner = JcaContentSignerBuilder(SIG_ALGO).setProvider(BC_PROVIDER).build(signerKey)
    val infoGen =
        JcaSignerInfoGeneratorBuilder(
            JcaDigestCalculatorProviderBuilder().setProvider(BC_PROVIDER).build(),
        ).build(contentSigner, signerCert)
    val gen =
        CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(infoGen)
            addCertificates(JcaCertStore(includedCerts))
        }
    return gen.generate(CMSProcessableByteArray(content), false).encoded
}

/**
 * Detached CMS / PKCS#7 signature that uses the **SubjectKeyIdentifier** flavor of
 * `SignerIdentifier` rather than the default `IssuerAndSerialNumber`. Apple's PassKit
 * signing infrastructure exclusively emits SKI signer-IDs (every Pass Type ID
 * certificate carries an SKI extension and Apple's signer references the leaf by SKI),
 * so this helper exists to give the verifier a fixture that exercises the SKI code
 * path in [firstSignerWithCert] — the path real Apple-signed pkpass archives travel.
 *
 * The SKI bytes are derived from the leaf certificate's SubjectKeyIdentifier extension
 * via [extractSubjectKeyIdentifier], matching the production wire shape Apple ships.
 */
internal fun cmsDetachedSignatureWithSki(
    content: ByteArray,
    signerCert: X509Certificate,
    signerKey: PrivateKey,
    includedCerts: List<X509Certificate>,
): ByteArray {
    ensureBouncyCastleProvider()
    val contentSigner = JcaContentSignerBuilder(SIG_ALGO).setProvider(BC_PROVIDER).build(signerKey)
    val infoGen =
        JcaSignerInfoGeneratorBuilder(
            JcaDigestCalculatorProviderBuilder().setProvider(BC_PROVIDER).build(),
        ).build(contentSigner, extractSubjectKeyIdentifier(signerCert))
    val gen =
        CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(infoGen)
            addCertificates(JcaCertStore(includedCerts))
        }
    return gen.generate(CMSProcessableByteArray(content), false).encoded
}

/**
 * Pulls the 20-byte SubjectKeyIdentifier extension value out of [cert]. Both
 * [selfSignedCertificate] and [signLeafCertificate] now attach this extension by
 * default so this lookup never returns null in tests; an unexpected null means a
 * test-only cert was minted without the extension and the test should fail loudly.
 */
private fun extractSubjectKeyIdentifier(cert: X509Certificate): ByteArray {
    val raw =
        cert.getExtensionValue(Extension.subjectKeyIdentifier.id)
            ?: error(
                "Test cert lacks SubjectKeyIdentifier extension; " +
                    "mint with selfSignedCertificate / signLeafCertificate",
            )
    // X509Certificate.getExtensionValue returns the DER encoding of the outer OCTET STRING
    // wrapping the extension value (RFC 5280 §4.1.2.9). For a SubjectKeyIdentifier extension
    // the inner value is itself an OCTET STRING containing the 20-byte key identifier
    // (RFC 5280 §4.2.1.2: `SubjectKeyIdentifier ::= OCTET STRING`). BC's parseExtensionValue
    // peels the outer OCTET STRING; one further unwrap pulls the raw key bytes.
    val inner = JcaX509ExtensionUtils.parseExtensionValue(raw)
    return SubjectKeyIdentifier.getInstance(inner).keyIdentifier
}

private fun JcaX509v3CertificateBuilder.addSubjectKeyIdentifier(publicKey: PublicKey) {
    val ski = JcaX509ExtensionUtils().createSubjectKeyIdentifier(publicKey)
    addExtension(Extension.subjectKeyIdentifier, false, ski)
}

private const val BC_PROVIDER = "BC"
private const val SIG_ALGO = "SHA256withRSA"
private const val RSA_KEY_BITS = 2048
private const val ONE_HOUR_MILLIS = 60L * 60L * 1000L
private const val ONE_YEAR_MILLIS = 365L * 24L * ONE_HOUR_MILLIS
