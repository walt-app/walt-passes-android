package `is`.walt.passes.export

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * Locks the public API surface of `passes-export`. Wire-format constants, sealed-type
 * arms, and schema version are intentionally brittle: a change here is a cross-platform
 * format change that must be deliberate and reviewed.
 */
class PublicApiSurfaceTest {

    // ── ExportConstants ───────────────────────────────────────────────────────

    @Test
    fun exportConstantsFormatIsStable() {
        assertThat(ExportConstants.FORMAT).isEqualTo("wallet-export")
    }

    @Test
    fun exportConstantsVersionIsOne() {
        assertThat(ExportConstants.VERSION).isEqualTo(1)
    }

    @Test
    fun exportConstantsAlgorithmIsAes256Gcm() {
        assertThat(ExportConstants.ALGORITHM_AES_256_GCM).isEqualTo("AES-256-GCM")
    }

    @Test
    fun exportConstantsFileExtensionIsWalt() {
        assertThat(ExportConstants.FILE_EXTENSION).isEqualTo(".walt")
    }

    @Test
    fun exportConstantsMimeTypeIsStable() {
        assertThat(ExportConstants.MIME_TYPE).isEqualTo("application/vnd.walt.wallet-export+json")
    }

    // ── WalletExportPayload ───────────────────────────────────────────────────

    @Test
    fun currentSchemaVersionIsOne() {
        assertThat(WalletExportPayload.CURRENT_SCHEMA_VERSION).isEqualTo(1)
    }

    // ── ExportKdfParams ───────────────────────────────────────────────────────

    @Test
    fun kdfParamsArmsAreReachableViaWhen() {
        fun arm(p: ExportKdfParams) = when (p) {
            is ExportKdfParams.Direct -> "direct"
            is ExportKdfParams.Pbkdf2HmacSha256 -> "pbkdf2"
        }
        assertThat(arm(ExportKdfParams.Direct)).isEqualTo("direct")
        assertThat(arm(ExportKdfParams.Pbkdf2HmacSha256(salt = "abc", iterations = 1))).isEqualTo("pbkdf2")
    }

    // ── WalletImportError ─────────────────────────────────────────────────────

    @Test
    fun importErrorArmsAreReachableViaWhen() {
        fun arm(e: WalletImportError) = when (e) {
            is WalletImportError.AuthenticationFailed -> "auth"
            is WalletImportError.WrongKdf -> "kdf:${e.expected}:${e.actual}"
            is WalletImportError.UnsupportedVersion -> "version:${e.found}"
            is WalletImportError.MalformedFile -> "malformed"
        }
        assertThat(arm(WalletImportError.AuthenticationFailed)).isEqualTo("auth")
        assertThat(arm(WalletImportError.WrongKdf("direct", "pbkdf2-hmac-sha256"))).isEqualTo("kdf:direct:pbkdf2-hmac-sha256")
        assertThat(arm(WalletImportError.UnsupportedVersion(99))).isEqualTo("version:99")
        assertThat(arm(WalletImportError.MalformedFile(RuntimeException("x")))).isEqualTo("malformed")
    }

    // ── Meta shapes ───────────────────────────────────────────────────────────

    @Test
    fun scannableCardMetaConstructorAndSerialNames() {
        val meta = ScannableCardMeta(label = "My card", format = "EAN_13", payload = "5012345678900")
        assertThat(meta.label).isEqualTo("My card")
        assertThat(meta.format).isEqualTo("EAN_13")
        assertThat(meta.payload).isEqualTo("5012345678900")
    }

    @Test
    fun pdfDocumentMetaConstructorFields() {
        val meta = PdfDocumentMeta(label = "Receipt", pageCount = 2, byteCount = 12_345L, provenance = "UserProvided")
        assertThat(meta.label).isEqualTo("Receipt")
        assertThat(meta.pageCount).isEqualTo(2)
        assertThat(meta.byteCount).isEqualTo(12_345L)
        assertThat(meta.provenance).isEqualTo("UserProvided")
    }

    @Test
    fun passMetaConstructorFields() {
        val meta = PassMeta(
            type = "BoardingPass",
            serialNumber = "SN-001",
            description = "Flight LH400",
            organizationName = "Lufthansa",
            expirationDate = null,
            voided = false,
            signatureStatus = "AppleVerified",
        )
        assertThat(meta.type).isEqualTo("BoardingPass")
        assertThat(meta.serialNumber).isEqualTo("SN-001")
        assertThat(meta.expirationDate).isNull()
        assertThat(meta.voided).isFalse()
        assertThat(meta.signatureStatus).isEqualTo("AppleVerified")
    }

    // ── buildEnvelope ─────────────────────────────────────────────────────────

    @Test
    fun buildEnvelopePopulatesAllFieldsFromArtifact() {
        val artifact = object : ExportableArtifact {
            override val exportKind = ArtifactKind.PKPASS
            override val exportId = "sn-42"
            override val exportCreatedAt = "2026-01-01T00:00:00Z"
        }
        val envelope = artifact.buildEnvelope(meta = JsonObject(emptyMap()))
        assertThat(envelope.kind).isEqualTo(ArtifactKind.PKPASS)
        assertThat(envelope.id).isEqualTo("sn-42")
        assertThat(envelope.createdAt).isEqualTo("2026-01-01T00:00:00Z")
        assertThat(envelope.blob).isNull()
    }

    @Test
    fun buildEnvelopeBase64EncodesBlob() {
        val artifact = object : ExportableArtifact {
            override val exportKind = ArtifactKind.PDF_DOCUMENT
            override val exportId = "doc-1"
            override val exportCreatedAt = "2026-01-01T00:00:00Z"
        }
        val blob = byteArrayOf(0x50, 0x44, 0x46) // "PDF"
        val envelope = artifact.buildEnvelope(meta = JsonObject(emptyMap()), blob = blob)
        assertThat(envelope.blob).isEqualTo(java.util.Base64.getEncoder().encodeToString(blob))
    }

    // ── WalletExportKey ───────────────────────────────────────────────────────

    @Test
    fun keySizeIs32Bytes() {
        assertThat(WalletExportKey.KEY_SIZE_BYTES).isEqualTo(32)
    }
}
