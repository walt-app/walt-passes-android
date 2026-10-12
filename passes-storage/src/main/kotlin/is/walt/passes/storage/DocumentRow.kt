package `is`.walt.passes.storage

import `is`.walt.passes.core.ScannableFormat

/**
 * Defensive caps `passes-storage` re-checks before inserting a document row, so a caller bug or
 * a new entry path cannot land an oversized blob in the encrypted database.
 *
 * [MAX_BYTES] and [MAX_PAGES] copy the import caps (ADR 0005 D7) because storage has no production
 * edge to `passes-document-core`; `DocumentBoundsParityTest` keeps them equal to `PdfImportConfig`.
 *
 * [MAX_LABEL_CHARS] is enforced only here. Nothing upstream bounds the consumer-supplied
 * display label, and the column is used to render the indexed list view, so a multi-MB
 * string would inflate every list-view query.
 */
public object DocumentBounds {
    public const val MAX_BYTES: Long = 25L * 1024 * 1024
    public const val MAX_PAGES: Int = 10
    public const val MAX_LABEL_CHARS: Int = 256
}

/**
 * The container kind of a stored document — the `documents.format` discriminator. The
 * storage layer keeps its own enum (rather than importing `passes-document-core`'s `Document`
 * arms or the importer's `ImageFormat`) because `passes-storage` is an independent peer of
 * both: the `Document <-> documents-table` mapping is a consumer-defined seam. [Pdf] maps to
 * a [PdfDocument][`is`.walt.passes.document.PdfDocument]; the image arms map to an
 * [ImageDocument][`is`.walt.passes.document.ImageDocument]. Persisted as the lowercased name
 * ('pdf' / 'png' / 'jpeg' / 'webp'); reordering arms is safe because the column stores the
 * name, not the ordinal.
 */
public enum class DocumentFormat {
    Pdf,
    Png,
    Jpeg,
    WebP,
}

/**
 * What [PassRepository.insertDocument] persists, as a sealed discriminator over the document
 * kinds the `documents` table now holds (wpass-i9x). Each arm carries the kind-specific
 * fields — page count for PDFs, decoded pixel dimensions for images — while sharing the
 * common label / original bytes / thumbnail bytes. Modelled as a sealed interface (not a
 * widened parameter list of nullables) so a caller cannot construct a nonsensical mix
 * (an image with a page count, a PDF with dimensions); the type makes each kind's fields
 * exactly the ones that apply.
 *
 * [bytes] is the ORIGINAL document bytes (PDF bytes, or the original compressed image
 * bytes); storage round-trips them as an opaque BLOB and never decodes them. [thumbnailBytes]
 * is the Walt-produced display raster encoded as PNG upstream.
 */
public sealed interface DocumentInsert {
    public val label: String
    public val bytes: ByteArray
    public val thumbnailBytes: ByteArray

    /** A PDF document. [pageCount] is the kind-specific field (ADR 0005 D7 caps it). */
    public data class Pdf(
        public override val label: String,
        public override val bytes: ByteArray,
        public override val thumbnailBytes: ByteArray,
        public val pageCount: Int,
    ) : DocumentInsert

    /**
     * A still-image document. [format] is one of the image arms of [DocumentFormat]
     * (passing [DocumentFormat.Pdf] here is a caller bug; the image import path supplies a
     * sniffed image format). [widthPx] / [heightPx] are the decoded raster dimensions from
     * the `passes-image` sandbox.
     */
    public data class Image(
        public override val label: String,
        public override val bytes: ByteArray,
        public override val thumbnailBytes: ByteArray,
        public val format: DocumentFormat,
        public val widthPx: Int,
        public val heightPx: Int,
    ) : DocumentInsert

    /**
     * A composite artifact (wpass-8lu): a still image plus a barcode extracted from it, persisted
     * as ONE row. The image half is identical to [Image] — [format] is one of the image arms of
     * [DocumentFormat], [widthPx] / [heightPx] are the sandbox raster dimensions. The barcode
     * half is [barcodePayload] (the decoded symbol contents) plus [barcodeFormat] (the symbology
     * it was detected as). Passing [DocumentFormat.Pdf] as [format] is a caller bug; a composite
     * is always image-backed.
     */
    public data class BarcodedImage(
        public override val label: String,
        public override val bytes: ByteArray,
        public override val thumbnailBytes: ByteArray,
        public val format: DocumentFormat,
        public val widthPx: Int,
        public val heightPx: Int,
        public val barcodePayload: String,
        public val barcodeFormat: ScannableFormat,
    ) : DocumentInsert
}

/**
 * The list-view projection of a stored document (PDF or image). Mirrors the indexed columns
 * of the `documents` table; the heavy `pdf_bytes` and `document_thumbnails.bytes` blobs are
 * NOT loaded here. Consumers that need the bytes call [PassRepository.loadDocumentBytes] /
 * [PassRepository.loadDocumentThumbnail].
 *
 * [format] is the discriminator a consumer branches on to rebuild the right
 * [Document][`is`.walt.passes.document.Document] arm: [DocumentFormat.Pdf] uses [pageCount];
 * the image formats use [widthPx] / [heightPx]. The fields not relevant to a row's kind are
 * the column defaults — [pageCount] is `1` for image rows (an image is a single page),
 * [widthPx] / [heightPx] are `null` for PDF rows.
 *
 * [barcodePayload] / [barcodeFormat] are non-null ONLY for a composite artifact (wpass-8lu) —
 * an image row that also carries a barcode extracted from it. A consumer rebuilds a
 * `BarcodedImageDocument` when both are present and an `ImageDocument` otherwise; they are
 * always `null` for PDF rows and for plain image rows. The pair is the composite discriminator
 * on top of the image [format] (the barcode does not change the container format).
 */
public data class DocumentRow(
    public val id: DocumentRecordId,
    public val displayLabel: String,
    public val byteCount: Long,
    public val format: DocumentFormat,
    public val pageCount: Int,
    public val widthPx: Int?,
    public val heightPx: Int?,
    public val importedAtEpochMs: Long,
    public val barcodePayload: String? = null,
    public val barcodeFormat: ScannableFormat? = null,
)
