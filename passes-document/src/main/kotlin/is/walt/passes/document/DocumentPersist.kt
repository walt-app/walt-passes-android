package `is`.walt.passes.document

import `is`.walt.passes.core.ScannableFormat

/**
 * What [DocumentImporter] hands its `persist` callback on the success path — a sealed
 * discriminator over the document kinds. The consumer's lambda maps this to
 * `passes-storage`'s `DocumentInsert` (PDF → `DocumentInsert.Pdf`, image →
 * `DocumentInsert.Image`, composite → `DocumentInsert.BarcodedImage`); the importer stays
 * storage-agnostic, exactly as `PdfImporter`'s callback keeps `passes-pdf` and `passes-storage`
 * independent peers.
 *
 * Modelled as a sealed interface (not a widened nullable parameter list) so the consumer's
 * mapping is exhaustive and a caller cannot persist an image with a page count or a PDF with
 * pixel dimensions.
 *
 * [bytes] is always the ORIGINAL document bytes (PDF bytes, or the original compressed image
 * bytes) — persist them verbatim; this is the "persist original" half of the import contract.
 * [thumbnailBytes] is a Walt-produced PNG: page-0 for a PDF, the bounded sandbox raster for an
 * image.
 */
public sealed interface DocumentPersist {
    public val label: String
    public val bytes: ByteArray
    public val thumbnailBytes: ByteArray

    public data class Pdf(
        public override val label: String,
        public override val bytes: ByteArray,
        public override val thumbnailBytes: ByteArray,
        public val pageCount: Int,
    ) : DocumentPersist

    public data class Image(
        public override val label: String,
        public override val bytes: ByteArray,
        public override val thumbnailBytes: ByteArray,
        public val format: ImageFormat,
        public val widthPx: Int,
        public val heightPx: Int,
    ) : DocumentPersist

    /**
     * A composite artifact (wpass-8lu): an image plus a barcode extracted from it, persisted as
     * ONE row. The image half mirrors [Image]; [barcodePayload] / [barcodeFormat] are the
     * isolated-decode result. The consumer maps this to `DocumentInsert.BarcodedImage`.
     */
    public data class BarcodedImage(
        public override val label: String,
        public override val bytes: ByteArray,
        public override val thumbnailBytes: ByteArray,
        public val format: ImageFormat,
        public val widthPx: Int,
        public val heightPx: Int,
        public val barcodePayload: String,
        public val barcodeFormat: ScannableFormat,
    ) : DocumentPersist
}
