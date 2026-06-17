package `is`.walt.passes.document

import android.content.Context
import `is`.walt.passes.core.ScannableFormat

/**
 * The single document-import entry point. Magic-byte-sniffs the source as PDF or image and
 * branches to the right isolated backend — `passes-pdf`'s renderer service for PDFs (via
 * `PdfImporter`), `passes-image`'s decode sandbox for images (via `BoundedImageDecoder`) —
 * then folds the per-backend outcome onto the unified [DocumentImportResult].
 *
 * This is the seam wpass-i9x adds so the sniff-and-branch orchestration is a single audited
 * surface in this repository rather than reassembled in walt-android. It owns ONLY the
 * branch, the one-shot bounded read, and the result mapping; every byte of decode/render work
 * still happens inside the isolated backends. The trust claim is the same one `PdfImporter`'s
 * KDoc makes: composing this by hand in the consumer would be the parallel-implementation
 * pattern the repository's DECISIVE CONSTRAINT forbids.
 *
 * Composite artifacts (wpass-8lu): the image branch additionally runs the isolated still-image
 * barcode decoder (`passes-barcode`) over the SAME once-read bytes. When a code is found the
 * import yields a [DocumentImportResult.ImportedBarcodedImage]; when none is found (or the
 * extraction fails, or the consumer declines via `confirmBarcode`) it degrades to a plain
 * [DocumentImportResult.ImportedImage]. Barcode extraction never runs in the host process; only
 * the decoded payload + symbology cross the binder, matching the wpass-i9x isolation invariant.
 *
 * Storage is wired through a `persist` callback rather than a `PassRepository` dependency so
 * `passes-document` stays independent of `passes-storage` (matching `PdfImporter`). The
 * consumer's Hilt graph supplies a lambda that maps [DocumentPersist] to
 * `PassRepository.insertDocument`.
 */
public interface DocumentImporter {
    /**
     * Run the import end-to-end. Returns [DocumentImportResult.ImportedPdf] /
     * [DocumentImportResult.ImportedImage] on success, [DocumentImportResult.Unrecognized] if
     * the bytes are neither a PDF nor a supported image, or the matching per-backend reject
     * arm otherwise. Backends are unbound before this returns regardless of outcome.
     *
     * [persist] is invoked exactly once on the success path, before the `Imported*` arm is
     * constructed, and never on a rejection. If it throws (other than `CancellationException`,
     * which propagates to preserve structured concurrency) the import returns
     * [DocumentImportResult.StorageHandoffFailed].
     *
     * [displayLabel] is supplied by the consumer; the importer never derives it from the
     * source's metadata (ADR 0005 D4). It is forwarded verbatim to [persist].
     *
     * [confirmBarcode] gates the composite path (wpass-8lu): for an image whose isolated barcode
     * extraction found a code, it is invoked with the decoded `(payload, format)` BEFORE anything
     * is persisted, so the consumer can show its `BarcodeCreateConfirmSheet` and let the user
     * verify the read (a misread barcode at a checkout is a real failure). Returning `true`
     * persists a composite ([DocumentPersist.BarcodedImage]); returning `false` degrades to a
     * plain image ([DocumentPersist.Image]). The default accepts every read, so a consumer that
     * does not gate gets the composite whenever a code is present. It is never called for PDFs,
     * for images with no detected barcode, or when extraction fails. A `CancellationException`
     * from the hook propagates; any other throw is treated as a declined confirmation (degrade
     * to image) so a confirm-UI bug cannot fail the whole import.
     */
    public suspend fun import(
        source: DocumentImportSource,
        displayLabel: String,
        confirmBarcode: suspend (payload: String, format: ScannableFormat) -> Boolean =
            { _, _ -> true },
        persist: suspend (DocumentPersist) -> Unit,
    ): DocumentImportResult

    public companion object {
        public fun create(
            context: Context,
            config: DocumentImportConfig = DocumentImportConfig(),
        ): DocumentImporter = DefaultDocumentImporter.create(context, config)
    }
}
