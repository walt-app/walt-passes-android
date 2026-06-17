package `is`.walt.passes.document

import `is`.walt.passes.image.android.ImageDecodeRejectedKind

/**
 * The outcome of [DocumentImporter.import]. Sealed so the consumer gets compile-time
 * exhaustiveness, mirroring `PdfImportResult` / `ParseResult`.
 *
 * The two reject arms REUSE each backend's existing taxonomy verbatim rather than flattening
 * them into one enum (the design decision wpass-bsf calls out): [PdfRejected] carries
 * `passes-document-core`'s [DocumentRejectedKind]; [ImageRejected] carries `passes-image`'s
 * [ImageDecodeRejectedKind]. Folding them together would force every consumer to branch on
 * arms that cannot occur for a given kind (a PDF is never `DimensionsTooLarge`; an image is
 * never `Encrypted`). The importer-facing reject types therefore live nowhere new — there is
 * no third "document reject" enum to keep in sync.
 *
 * Two cross-cutting outcomes are hoisted to their own arms because they are not kind-specific:
 *
 *  - [Unrecognized] — the bytes sniffed as neither a PDF nor a supported image. There is no
 *    backend to attribute this to, so it is its own arm rather than a borrowed PDF/image
 *    reject value.
 *  - [StorageHandoffFailed] — the consumer's `persist` callback threw after a successful
 *    decode/render. Shared across kinds: the failure is in the consumer's storage layer, not
 *    in either isolated backend, so it reads the same whether the document was a PDF or an
 *    image.
 */
public sealed interface DocumentImportResult {
    public data class ImportedPdf(public val doc: PdfDocument) : DocumentImportResult

    public data class ImportedImage(public val doc: ImageDocument) : DocumentImportResult

    /**
     * A composite artifact was imported (wpass-8lu): an image whose isolated barcode extraction
     * found a code AND (when a `confirmBarcode` hook is supplied) the consumer confirmed it. The
     * [doc]'s `barcodePayload` is the value the consumer already saw in its confirm step. An
     * image with no detected barcode, a failed/rejected extraction, or a declined confirmation
     * lands on [ImportedImage] instead — the composite arm exists only for a live, kept barcode.
     */
    public data class ImportedBarcodedImage(
        public val doc: BarcodedImageDocument,
    ) : DocumentImportResult

    public data class PdfRejected(public val kind: DocumentRejectedKind) : DocumentImportResult

    public data class ImageRejected(public val kind: ImageDecodeRejectedKind) : DocumentImportResult

    public data object Unrecognized : DocumentImportResult

    public data object StorageHandoffFailed : DocumentImportResult
}
