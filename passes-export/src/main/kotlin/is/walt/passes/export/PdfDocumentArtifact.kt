package `is`.walt.passes.export

import `is`.walt.passes.export.internal.WalletExportJson
import `is`.walt.passes.pdf.PdfDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.Base64

/** Structured metadata stored in [ArtifactEnvelope.meta] for [ArtifactKind.PDF_DOCUMENT]. */
@Serializable
public data class PdfDocumentMeta(
    @SerialName("label") public val label: String,
    @SerialName("page_count") public val pageCount: Int,
    @SerialName("byte_count") public val byteCount: Long,
    @SerialName("provenance") public val provenance: String,
)

/**
 * Converts this document to an [ArtifactEnvelope], embedding [pdfBytes] as base64 in
 * [ArtifactEnvelope.blob]. The caller is responsible for sourcing the bytes from storage
 * (`DocumentStore.loadBytes`).
 */
public fun PdfDocument.toArtifactEnvelope(pdfBytes: ByteArray): ArtifactEnvelope = ArtifactEnvelope(
    kind = exportKind,
    id = exportId,
    createdAt = exportCreatedAt,
    meta = WalletExportJson.encodeToJsonElement(
        PdfDocumentMeta(
            label = displayLabel,
            pageCount = pageCount,
            byteCount = byteCount,
            provenance = provenance.name,
        ),
    ).jsonObject,
    blob = Base64.getEncoder().encodeToString(pdfBytes),
)
