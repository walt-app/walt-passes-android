package `is`.walt.passes.export

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * Round-trip tests for the encrypt → decrypt path. Tests use a fixed clock so the
 * [WalletExportPayload.exportedAt] field is deterministic and assertable.
 *
 * These tests cover the crypto and serialization machinery only — artifact-specific
 * mapping (Pass → ArtifactEnvelope, etc.) is tested in the mapper tests.
 */
class WalletExporterRoundTripTest {

    private val fixedClock: () -> Long = { FIXED_EPOCH_MS }
    private val exporter = WalletExporter(clock = fixedClock)
    private val importer = WalletImporter()

    // ── Direct-key path ───────────────────────────────────────────────────────

    @Test
    fun directKeyRoundTripsPayload() {
        val key = WalletExportKey.generate()
        val file = exporter.export(emptyPayload(), key)
        val result = importer.decrypt(file, key)

        assertThat(result.isSuccess).isTrue()
        val payload = result.getOrThrow()
        assertThat(payload.schemaVersion).isEqualTo(WalletExportPayload.CURRENT_SCHEMA_VERSION)
        assertThat(payload.platform).isEqualTo("android")
        assertThat(payload.artifacts).isEmpty()
    }

    @Test
    fun exporterStampsExportedAt() {
        val key = WalletExportKey.generate()
        val file = exporter.export(emptyPayload(), key)
        val payload = importer.decrypt(file, key).getOrThrow()

        // Exact ISO-8601 string for FIXED_EPOCH_MS.
        assertThat(payload.exportedAt).isEqualTo(FIXED_EXPORTED_AT)
    }

    @Test
    fun exporterOverridesSchemaVersionFromCaller() {
        val key = WalletExportKey.generate()
        val stalePayload = emptyPayload().copy(schemaVersion = 0)
        val payload = importer.decrypt(exporter.export(stalePayload, key), key).getOrThrow()

        assertThat(payload.schemaVersion).isEqualTo(WalletExportPayload.CURRENT_SCHEMA_VERSION)
    }

    @Test
    fun outerEnvelopeRecordsDirect() {
        val file = exporter.export(emptyPayload(), WalletExportKey.generate())
        assertThat(file.walt.kdf).isEqualTo(ExportKdfParams.Direct)
        assertThat(file.walt.encryption.algorithm).isEqualTo(ExportConstants.ALGORITHM_AES_256_GCM)
        assertThat(file.walt.format).isEqualTo(ExportConstants.FORMAT)
        assertThat(file.walt.version).isEqualTo(ExportConstants.VERSION)
    }

    @Test
    fun wrongKeyFailsWithAuthenticationFailed() {
        val key = WalletExportKey.generate()
        val file = exporter.export(emptyPayload(), key)
        val wrongKey = WalletExportKey.generate()

        val result = importer.decrypt(file, wrongKey)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(WalletImportError.AuthenticationFailed::class.java)
    }

    @Test
    fun callingDecryptWithPassphraseOnDirectFileFailsWithWrongKdf() {
        val file = exporter.export(emptyPayload(), WalletExportKey.generate())
        val result = importer.decryptWithPassphrase(file, "passphrase".toCharArray())

        assertThat(result.isFailure).isTrue()
        val error = result.exceptionOrNull() as? WalletImportError.WrongKdf
        assertThat(error).isNotNull()
        assertThat(error!!.expected).isEqualTo("pbkdf2-hmac-sha256")
        assertThat(error.actual).isEqualTo("direct")
    }

    // ── Passphrase path ───────────────────────────────────────────────────────

    @Test
    fun passphraseRoundTrips() {
        val file = exporter.exportWithPassphrase(emptyPayload(), PASSPHRASE.toCharArray())
        val result = importer.decryptWithPassphrase(file, PASSPHRASE.toCharArray())

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().platform).isEqualTo("android")
    }

    @Test
    fun outerEnvelopeRecordsPbkdf2Params() {
        val file = exporter.exportWithPassphrase(emptyPayload(), PASSPHRASE.toCharArray())
        val kdf = file.walt.kdf as? ExportKdfParams.Pbkdf2HmacSha256

        assertThat(kdf).isNotNull()
        assertThat(kdf!!.iterations).isEqualTo(600_000)
        assertThat(kdf.salt).isNotEmpty()
    }

    @Test
    fun wrongPassphraseFails() {
        val file = exporter.exportWithPassphrase(emptyPayload(), PASSPHRASE.toCharArray())
        val result = importer.decryptWithPassphrase(file, "wrong passphrase".toCharArray())

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(WalletImportError.AuthenticationFailed::class.java)
    }

    @Test
    fun callingDecryptOnPassphraseFileFailsWithWrongKdf() {
        val file = exporter.exportWithPassphrase(emptyPayload(), PASSPHRASE.toCharArray())
        val result = importer.decrypt(file, WalletExportKey.generate())

        assertThat(result.isFailure).isTrue()
        val error = result.exceptionOrNull() as? WalletImportError.WrongKdf
        assertThat(error).isNotNull()
        assertThat(error!!.expected).isEqualTo("direct")
        assertThat(error.actual).isEqualTo("pbkdf2-hmac-sha256")
    }

    // ── Artifact envelope preservation ───────────────────────────────────────

    @Test
    fun artifactsRoundTripVerbatim() {
        val envelope = ArtifactEnvelope(
            kind = ArtifactKind.SCANNABLE_CARD,
            id = "card-001",
            createdAt = "2026-01-01T00:00:00Z",
            meta = buildJsonObject {
                put("label", "My Card")
                put("format", "scannable_card")
                put("payload", "HELLO")
            },
            blob = null,
        )
        val key = WalletExportKey.generate()
        val file = exporter.export(emptyPayload().copy(artifacts = listOf(envelope)), key)
        val decoded = importer.decrypt(file, key).getOrThrow()

        assertThat(decoded.artifacts).hasSize(1)
        with(decoded.artifacts[0]) {
            assertThat(kind).isEqualTo(ArtifactKind.SCANNABLE_CARD)
            assertThat(id).isEqualTo("card-001")
            assertThat(createdAt).isEqualTo("2026-01-01T00:00:00Z")
            assertThat(blob).isNull()
        }
    }

    @Test
    fun unknownArtifactKindIsPreservedVerbatim() {
        val future = ArtifactEnvelope(
            kind = "future_artifact_type",
            id = "future-001",
            createdAt = "2030-01-01T00:00:00Z",
            meta = buildJsonObject { put("data", "opaque") },
            blob = null,
        )
        val key = WalletExportKey.generate()
        val decoded = importer.decrypt(
            exporter.export(emptyPayload().copy(artifacts = listOf(future)), key),
            key,
        ).getOrThrow()

        assertThat(decoded.artifacts[0].kind).isEqualTo("future_artifact_type")
        assertThat(decoded.artifacts[0].id).isEqualTo("future-001")
    }

    @Test
    fun blobRoundTrips() {
        val rawBytes = "PDF bytes here".encodeToByteArray()
        val b64 = java.util.Base64.getEncoder().encodeToString(rawBytes)
        val envelope = ArtifactEnvelope(
            kind = ArtifactKind.PDF_DOCUMENT,
            id = "doc-001",
            createdAt = "2026-01-01T00:00:00Z",
            meta = buildJsonObject { put("label", "Receipt") },
            blob = b64,
        )
        val key = WalletExportKey.generate()
        val decoded = importer.decrypt(
            exporter.export(emptyPayload().copy(artifacts = listOf(envelope)), key),
            key,
        ).getOrThrow()

        assertThat(decoded.artifacts[0].blob).isEqualTo(b64)
    }

    // ── Outer file serialization ──────────────────────────────────────────────

    @Test
    fun parseFileRoundTrips() {
        val key = WalletExportKey.generate()
        val file = exporter.export(emptyPayload(), key)
        val json = `is`.walt.passes.export.internal.WalletExportJson.encodeToString(
            WalletExportFile.serializer(),
            file,
        )
        val parsed = importer.parseFile(json).getOrThrow()
        val payload = importer.decrypt(parsed, key).getOrThrow()

        assertThat(payload.exportedAt).isEqualTo(FIXED_EXPORTED_AT)
    }

    @Test
    fun parseFileRejectsMalformedJson() {
        val result = importer.parseFile("not json at all {{{")
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(WalletImportError.MalformedFile::class.java)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun emptyPayload() = WalletExportPayload(
        schemaVersion = WalletExportPayload.CURRENT_SCHEMA_VERSION,
        exportedAt = "",
        platform = "android",
        artifacts = emptyList(),
        preferences = WalletPreferences(),
        extensions = JsonObject(emptyMap()),
    )

    private companion object {
        const val FIXED_EPOCH_MS = 1_750_000_000_000L
        const val FIXED_EXPORTED_AT = "2025-06-15T15:06:40Z"
        const val PASSPHRASE = "correct horse battery staple"
    }
}
