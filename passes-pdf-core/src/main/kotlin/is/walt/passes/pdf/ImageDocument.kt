package `is`.walt.passes.pdf

/**
 * Opaque identifier for a stored [ImageDocument]. Wrapped in a value class so calling code
 * cannot accidentally substitute a `String` from another domain (a pass id, a filename, a
 * user input) into APIs that expect a document id. The [DocumentId] arm for images, sibling
 * to [PdfDocumentId].
 */
@JvmInline
public value class ImageDocumentId(public override val value: String) : DocumentId

/**
 * The pure-Kotlin model for a successfully-imported still image (PNG / JPEG / WebP) — the
 * second [Document] arm (wpass-i9x step 4), sibling to [PdfDocument]. Images are a
 * *sibling* of passes for the same reasons PDFs are (ADR 0005 D1): not signature-verified
 * (D5), trust caption sourced from [provenance], which has a single arm by design.
 *
 * [widthPx] / [heightPx] are the image-specific fields that live on this arm rather than on
 * the [Document] supertype, exactly as [PdfDocument.pageCount] does for PDFs. They are the
 * pixel dimensions of the bounded raster Walt decoded inside the isolated image-decode
 * sandbox (`passes-image`), never derived from an in-process decode of the untrusted source
 * bytes and never upscaled beyond the source. The original compressed bytes are persisted
 * verbatim; these dimensions are display/telemetry metadata, not a re-decoded canvas.
 *
 * The model deliberately carries no container format (PNG vs JPEG vs WebP): the format is a
 * persistence detail handled by `passes-storage`, and the display surface renders a
 * Walt-produced raster, not the original codec stream. Keeping format off the model mirrors
 * [PdfDocument] carrying no MIME and keeps `passes-pdf-core` free of the image-format enum,
 * which lives one layer up in the importer module.
 *
 * The displayed [displayLabel] is supplied at import time by the consumer; the model layer
 * never derives it from image metadata (EXIF, embedded XMP), which is part of the
 * no-extraction-from-content discipline (D4). Callers should pass a filename if they have
 * one and a date-based fallback otherwise.
 */
public data class ImageDocument(
    public override val id: ImageDocumentId,
    public override val displayLabel: String,
    public override val byteCount: Long,
    public val widthPx: Int,
    public val heightPx: Int,
    public override val importedAtEpochMs: Long,
    public override val provenance: Provenance = Provenance.UserProvided,
) : Document
