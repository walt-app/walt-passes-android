package `is`.walt.passes.core.internal

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
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
 * Cryptographic primitives (RSA key generation, X.509 self-signing, CMS detached
 * signature production) are delegated to [TestCryptoSupport]. That keeps the signing
 * math identical between this fixture and [SignatureVerifierTest]: a regression in one
 * suite is informative about the same path in the other, with no risk of the two
 * implementations drifting.
 */
internal object SyntheticPkpass {
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
        members[MANIFEST_FILE_NAME] = manifest
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
        members[MANIFEST_FILE_NAME] = manifest
        members[SIGNATURE_FILE_NAME] = buildSelfSignedSignature(manifest)
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
        return zip(linkedMapOf(PASS_JSON to tampered, MANIFEST_FILE_NAME to manifest))
    }

    /**
     * Compose a minimal valid pass.json for [type] (one of "boardingPass",
     * "eventTicket", "coupon", "storeCard", "generic"). Required top-level fields
     * (formatVersion, serialNumber, description, organizationName) are populated; the
     * style sub-object is empty, which is the smallest pass.json that decodes to the
     * named [`is`.walt.passes.core.PassType].
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
     * Build a syntactically valid PNG with arbitrary IHDR-declared dimensions —
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

    private fun buildSelfSignedSignature(manifest: ByteArray): ByteArray {
        val keys = newRsaKeyPair()
        val cert = selfSignedCertificate(keys, "CN=Test Synthetic Signer")
        return cmsDetachedSignature(
            content = manifest,
            signerCert = cert,
            signerKey = keys.private,
            includedCerts = listOf(cert),
        )
    }

    private const val PASS_JSON = "pass.json"
    private const val IHDR_DATA_LENGTH = 13
}
