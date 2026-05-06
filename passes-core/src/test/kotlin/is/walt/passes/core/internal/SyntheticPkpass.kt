package `is`.walt.passes.core.internal

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Test-only PKPASS archive builder. Produces fully-valid pkpass byte arrays — correct
 * `manifest.json` SHA-1 chain, optionally a self-signed CMS detached signature blob —
 * for use with the public [`is`.walt.passes.core.PassParser] entrypoint.
 *
 * Why hand-rolled rather than fixture binaries on disk:
 *   - A reviewer can read each test top-to-bottom and see exactly what shape the archive
 *     under test has. With opaque .pkpass binary fixtures, every `// see fixtures/foo.pkpass`
 *     hides whatever the binary actually contains and any future rebuild silently changes
 *     the test's input.
 *   - Self-signed RSA keys are generated per build, which keeps the test corpus from
 *     drifting against an Apple-issued cert that would expire and start failing the suite
 *     out of band.
 *
 * The signing path here mirrors [SignatureVerifierTest]'s helpers so the two suites stay
 * structurally identical; a failure in one is therefore informative about the other.
 */
internal object SyntheticPkpass {
    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Build an unsigned pkpass: zip with `pass.json`, `manifest.json`, optional images
     * and `<locale>.lproj/pass.strings` files, and no `signature` entry. Suitable for
     * lenient-default tests; trips strict mode by design.
     */
    fun unsigned(
        passJson: String,
        extraEntries: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val members = LinkedHashMap<String, ByteArray>()
        members[PASS_JSON] = passJson.toByteArray(Charsets.UTF_8)
        members.putAll(extraEntries)
        val manifest = buildManifest(members)
        members[MANIFEST_JSON] = manifest
        return zip(members)
    }

    /**
     * Build a signed pkpass with a synthesized self-signed certificate. The signature
     * verifies cryptographically against the manifest bytes; default [ParserConfig]
     * surfaces it as `SignatureStatus.SelfSigned` (no Apple anchor reachable). Strict
     * mode treats the same archive as `Tampered(SignatureCryptoFailure)`.
     */
    fun signedSelfSigned(
        passJson: String,
        extraEntries: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val members = LinkedHashMap<String, ByteArray>()
        members[PASS_JSON] = passJson.toByteArray(Charsets.UTF_8)
        members.putAll(extraEntries)
        val manifest = buildManifest(members)
        members[MANIFEST_JSON] = manifest
        members[SIGNATURE] = signCms(manifest)
        return zip(members)
    }

    /**
     * Build a signed archive then mutate one byte of `pass.json` post-manifest so the
     * declared SHA-1 no longer matches the actual contents. Used to exercise the
     * tamper-detection path through the public API.
     */
    fun unsignedWithTamperedPassJson(passJson: String): ByteArray {
        val passBytes = passJson.toByteArray(Charsets.UTF_8)
        val manifest = buildManifest(linkedMapOf(PASS_JSON to passBytes))
        // Swap one byte in pass.json AFTER the manifest captured its hash.
        val tampered = passBytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
        return zip(linkedMapOf(PASS_JSON to tampered, MANIFEST_JSON to manifest))
    }

    /**
     * Compose a minimal valid pass.json for [type] (one of "boardingPass",
     * "eventTicket", "coupon", "storeCard", "generic"). Required top-level fields
     * (formatVersion, serialNumber, description, organizationName) are populated; the
     * style sub-object is empty, which is the smallest pass.json that decodes to the
     * named [PassType].
     */
    fun minimalPassJson(
        type: String,
        serial: String = "S-001",
        organization: String = "Test Org",
        description: String = "Synthesized for tests",
    ): String =
        """
        {
            "formatVersion": 1,
            "serialNumber": "$serial",
            "description": "$description",
            "organizationName": "$organization",
            "$type": {}
        }
        """.trimIndent()

    /**
     * Build a syntactically valid 8x8 PNG with arbitrary IHDR-declared dimensions —
     * useful for exercising the pixel-cap check without serializing a real pixel grid.
     * The IHDR chunk is what the parser inspects; downstream chunks (IDAT, IEND) are
     * present so the byte stream looks like a PNG to any reader that walks past the
     * header.
     */
    fun fakePng(
        widthDeclared: Int = 1,
        heightDeclared: Int = 1,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A,
            ),
        )
        // IHDR length (13)
        out.writeIntBE(IHDR_DATA_LENGTH)
        out.write("IHDR".toByteArray(Charsets.US_ASCII))
        out.writeIntBE(widthDeclared)
        out.writeIntBE(heightDeclared)
        // bit depth, color type, compression, filter, interlace — fixed minimums
        out.write(
            byteArrayOf(
                0x08,
                0x00,
                0x00,
                0x00,
                0x00,
            ),
        )
        // CRC placeholder (parser does not validate the CRC; downstream renderers do)
        out.writeIntBE(0)
        // IEND chunk
        out.writeIntBE(0)
        out.write("IEND".toByteArray(Charsets.US_ASCII))
        out.writeIntBE(0)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeIntBE(value: Int) {
        write(value ushr 24 and 0xFF)
        write(value ushr 16 and 0xFF)
        write(value ushr 8 and 0xFF)
        write(value and 0xFF)
    }

    private fun buildManifest(members: Map<String, ByteArray>): ByteArray {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val pairs =
            members.entries.joinToString(",") { (name, bytes) ->
                val hex = HexFormat.of().formatHex(sha1.digest(bytes))
                "\"$name\":\"$hex\""
            }
        return "{$pairs}".toByteArray(Charsets.UTF_8)
    }

    private fun zip(members: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, bytes) in members) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun signCms(manifest: ByteArray): ByteArray {
        val keyGen = KeyPairGenerator.getInstance("RSA", "BC")
        keyGen.initialize(RSA_KEY_BITS)
        val keys = keyGen.generateKeyPair()
        val cert = selfSignedCertificate(keys.private, keys.public, "CN=Test Synthetic Signer")
        val signer =
            JcaContentSignerBuilder(SIG_ALGO).setProvider("BC").build(keys.private)
        val infoGen =
            JcaSignerInfoGeneratorBuilder(
                JcaDigestCalculatorProviderBuilder().setProvider("BC").build(),
            ).build(signer, cert)
        val gen =
            CMSSignedDataGenerator().apply {
                addSignerInfoGenerator(infoGen)
                addCertificates(JcaCertStore(listOf(cert)))
            }
        return gen.generate(CMSProcessableByteArray(manifest), false).encoded
    }

    private fun selfSignedCertificate(
        privateKey: PrivateKey,
        publicKey: java.security.PublicKey,
        subjectDn: String,
    ): X509Certificate {
        val now = System.currentTimeMillis()
        val name = X500Name(subjectDn)
        val builder =
            JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(now),
                Date(now - ONE_HOUR_MILLIS),
                Date(now + ONE_YEAR_MILLIS),
                name,
                publicKey,
            )
        val signer = JcaContentSignerBuilder(SIG_ALGO).setProvider("BC").build(privateKey)
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))
    }

    private const val PASS_JSON = "pass.json"
    private const val MANIFEST_JSON = "manifest.json"
    private const val SIGNATURE = "signature"
    private const val SIG_ALGO = "SHA256withRSA"
    private const val RSA_KEY_BITS = 2048
    private const val IHDR_DATA_LENGTH = 13
    private const val ONE_HOUR_MILLIS = 60L * 60L * 1000L
    private const val ONE_YEAR_MILLIS = 365L * 24L * ONE_HOUR_MILLIS
}
