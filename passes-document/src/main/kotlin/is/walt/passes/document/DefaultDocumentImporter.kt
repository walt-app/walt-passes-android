package `is`.walt.passes.document

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.os.SystemClock
import `is`.walt.passes.barcode.android.BarcodeImageDecoder
import `is`.walt.passes.barcode.android.BarcodeImageSource
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.image.android.BoundedImageDecoder
import `is`.walt.passes.image.android.ImageDecodeRejectedKind
import `is`.walt.passes.image.android.ImageDecodeResult
import `is`.walt.passes.image.android.ImageSource
import `is`.walt.passes.isolation.MemfdPfdFactory
import `is`.walt.passes.pdf.android.PdfImportSource
import `is`.walt.passes.pdf.android.PdfImporter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Persist callback shape the PDF backend ([PdfImporter]) expects: `(label, pdfBytes,
 * pageCount, thumbnailBytes)`. The orchestrator adapts it to the unified [DocumentPersist].
 */
internal typealias PdfPersist = suspend (String, ByteArray, Int, ByteArray) -> Unit

/**
 * Default [DocumentImporter]. The orchestration is the whole trust contribution and is kept
 * deliberately small and seam-injected so it is exercised without a live binder:
 *
 *  1. Read the source ONCE into a bounded buffer (`config.maxBytes + 1`). One read closes the
 *     offset-corruption hazard a peek-then-reopen would open on a one-shot fd source: whatever
 *     the backend receives is materialized from this exact buffer, not a second read of the
 *     source.
 *  2. Sniff: `isPdfHeader` → PDF arm; else `sniffImageFormat` → image arm; else
 *     [DocumentImportResult.Unrecognized].
 *  3. Hand the buffered bytes to the chosen backend through the [pdfImport] / [imageDecode]
 *     seams. The production seams (built in [create]) materialize the bytes into a sealed
 *     in-RAM `memfd` PFD and bind the isolated service; oversize and the per-kind caps are
 *     enforced inside the backend (the buffer is capped at `maxBytes + 1`, exactly enough for
 *     the backend to observe an over-cap file without ever holding the whole thing).
 *  4. Fold the per-backend outcome onto [DocumentImportResult].
 *
 * `StorageHandoffFailed` is hoisted to a shared arm for both kinds: the PDF backend's own
 * [DocumentRejectedKind.StorageHandoffFailed] is translated to it so a persist failure reads
 * the same regardless of document kind.
 */
// LongParameterList: every parameter is an injected seam (three isolated-backend seams plus the
// two clocks and the id generator) so the orchestration is unit-tested without a live binder.
// Bundling them into a holder would only relocate the list and obscure what each seam fakes.
@Suppress("LongParameterList")
internal class DefaultDocumentImporter(
    private val config: DocumentImportConfig,
    private val pdfImport: suspend (ByteArray, String, PdfPersist) -> PdfImportResult,
    private val imageDecode: suspend (ByteArray, Int) -> ImageDecodeOutcome,
    // Composite-artifact seam (wpass-8lu): runs the isolated still-image barcode decoder over the
    // SAME once-read bytes the image decode saw, on its own memfd. Returns the pure
    // BarcodeDecodeResult; the host never decodes the source bytes. Seam-injected so the
    // orchestration is unit-tested without a live binder.
    private val barcodeExtract: suspend (ByteArray) -> BarcodeDecodeResult,
    // Monotonic clock for telemetry durations only. Must NOT be used for importedAt: the
    // [wallClock] below supplies wall time for the persisted record.
    private val now: () -> Long,
    // Wall clock for the stamped importedAt. Seam-injected so a test can pin it; `now` cannot
    // serve here because it is monotonic (elapsedRealtime), not epoch time.
    private val wallClock: () -> Long,
    private val idGenerator: () -> String,
) : DocumentImporter {

    override suspend fun import(
        source: DocumentImportSource,
        displayLabel: String,
        confirmBarcode: suspend (payload: String, format: ScannableFormat) -> Boolean,
        persist: suspend (DocumentPersist) -> Unit,
    ): DocumentImportResult {
        val bytes = readBounded(source) ?: return DocumentImportResult.Unrecognized
        return when {
            isPdfHeader(bytes) -> importPdf(bytes, displayLabel, persist)
            else -> {
                val format = sniffImageFormat(bytes)
                if (format != null) {
                    importImage(bytes, format, displayLabel, persist, confirmBarcode)
                } else {
                    DocumentImportResult.Unrecognized
                }
            }
        }
    }

    private suspend fun importPdf(
        bytes: ByteArray,
        displayLabel: String,
        persist: suspend (DocumentPersist) -> Unit,
    ): DocumentImportResult {
        val adapter: PdfPersist = { label, pdfBytes, pageCount, thumbnailBytes ->
            persist(
                DocumentPersist.Pdf(
                    label = label,
                    bytes = pdfBytes,
                    thumbnailBytes = thumbnailBytes,
                    pageCount = pageCount,
                ),
            )
        }
        return when (val result = pdfImport(bytes, displayLabel, adapter)) {
            is PdfImportResult.Imported -> DocumentImportResult.ImportedPdf(result.doc)
            is PdfImportResult.Rejected ->
                if (result.kind == DocumentRejectedKind.StorageHandoffFailed) {
                    DocumentImportResult.StorageHandoffFailed
                } else {
                    DocumentImportResult.PdfRejected(result.kind)
                }
        }
    }

    // ReturnCount: each stage (decode reject, persist failure, success) is its own
    // short-circuit, matching the precedent DefaultPdfImporter sets; collapsing them hides
    // the stage shape behind monadic plumbing that does not pay for itself at this size.
    @Suppress("ReturnCount")
    private suspend fun importImage(
        bytes: ByteArray,
        format: ImageFormat,
        displayLabel: String,
        persist: suspend (DocumentPersist) -> Unit,
        confirmBarcode: suspend (String, ScannableFormat) -> Boolean,
    ): DocumentImportResult {
        val startedAt = now()
        val guard = config.imageTelemetryGuard
        guard.onImportStarted()

        val decoded = when (val outcome = imageDecode(bytes, config.maxImageDecodePx)) {
            is ImageDecodeOutcome.Rejected -> {
                guard.onImportFailed(
                    ImageImportFailedEvent(ImageImportFailureKind.Decode, now() - startedAt),
                )
                return DocumentImportResult.ImageRejected(outcome.kind)
            }
            is ImageDecodeOutcome.Decoded -> outcome
        }

        // Composite path (wpass-8lu): extract a barcode from the same bytes in the isolated
        // decoder. A found+confirmed code makes this a composite; anything else (no code,
        // extraction failure, declined confirmation) degrades to a plain image — extraction never
        // fails the import. Resolved BEFORE persist so nothing is stored until the consumer's
        // confirm step has run ("before the code becomes usable").
        val barcode = extractConfirmedBarcode(bytes, confirmBarcode)

        runCatching {
            persist(buildPersist(barcode, displayLabel, bytes, decoded, format))
        }.getOrElse { t ->
            if (t is CancellationException) throw t
            guard.onImportFailed(
                ImageImportFailedEvent(ImageImportFailureKind.StorageHandoff, now() - startedAt),
            )
            return DocumentImportResult.StorageHandoffFailed
        }

        val byteCount = bytes.size.toLong()
        // Same no-PII telemetry event for both arms: it carries only byte count / format /
        // dimensions, never the decoded payload, so a composite import emits no barcode contents.
        guard.onImportSucceeded(
            ImageImportSucceededEvent(
                byteCount = byteCount,
                format = format,
                widthPx = decoded.widthPx,
                heightPx = decoded.heightPx,
                durationMillis = now() - startedAt,
            ),
        )
        return buildImportedResult(barcode, displayLabel, byteCount, decoded)
    }

    private fun buildImportedResult(
        barcode: DecodedBarcode?,
        displayLabel: String,
        byteCount: Long,
        decoded: ImageDecodeOutcome.Decoded,
    ): DocumentImportResult {
        val importedAt = wallClock()
        return if (barcode != null) {
            DocumentImportResult.ImportedBarcodedImage(
                BarcodedImageDocument(
                    id = BarcodedImageDocumentId(idGenerator()),
                    displayLabel = displayLabel,
                    byteCount = byteCount,
                    widthPx = decoded.widthPx,
                    heightPx = decoded.heightPx,
                    barcodePayload = barcode.payload,
                    barcodeFormat = barcode.format,
                    importedAtEpochMs = importedAt,
                ),
            )
        } else {
            DocumentImportResult.ImportedImage(
                ImageDocument(
                    id = ImageDocumentId(idGenerator()),
                    displayLabel = displayLabel,
                    byteCount = byteCount,
                    widthPx = decoded.widthPx,
                    heightPx = decoded.heightPx,
                    importedAtEpochMs = importedAt,
                ),
            )
        }
    }

    /**
     * Runs the isolated barcode extraction and the consumer's confirm gate. Returns the
     * `(payload, format)` to persist as a composite, or `null` to degrade to a plain image —
     * for no detected code, an extraction failure, OR a declined/failed confirmation. A
     * `CancellationException` from the confirm hook propagates (structured concurrency); any
     * other throw is swallowed to a declined confirmation so a confirm-UI bug cannot fail the
     * whole import.
     */
    private suspend fun extractConfirmedBarcode(
        bytes: ByteArray,
        confirmBarcode: suspend (String, ScannableFormat) -> Boolean,
    ): DecodedBarcode? {
        val decoded = barcodeExtract(bytes) as? BarcodeDecodeResult.DecodedBarcode ?: return null
        val confirmed = try {
            confirmBarcode(decoded.payload, decoded.format)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
        return if (confirmed) DecodedBarcode(decoded.payload, decoded.format) else null
    }

    private fun buildPersist(
        barcode: DecodedBarcode?,
        displayLabel: String,
        bytes: ByteArray,
        decoded: ImageDecodeOutcome.Decoded,
        format: ImageFormat,
    ): DocumentPersist =
        if (barcode != null) {
            DocumentPersist.BarcodedImage(
                label = displayLabel,
                bytes = bytes,
                thumbnailBytes = decoded.thumbnailBytes,
                format = format,
                widthPx = decoded.widthPx,
                heightPx = decoded.heightPx,
                barcodePayload = barcode.payload,
                barcodeFormat = barcode.format,
            )
        } else {
            DocumentPersist.Image(
                label = displayLabel,
                bytes = bytes,
                thumbnailBytes = decoded.thumbnailBytes,
                format = format,
                widthPx = decoded.widthPx,
                heightPx = decoded.heightPx,
            )
        }

    private fun readBounded(source: DocumentImportSource): ByteArray? {
        val stream = openSource(source) ?: return null
        return stream.use { drainBounded(it, config.maxBytes) }
    }

    private fun openSource(source: DocumentImportSource): InputStream? =
        when (source) {
            is DocumentImportSource.ContentUri -> {
                // Scheme allowlist mirrors PdfImportSource / ImageSource: openInputStream would
                // happily resolve a `file://` URI to an arbitrary path, the escape hatch the
                // sealed source shape exists to close.
                if (source.uri.scheme != ContentResolver.SCHEME_CONTENT) {
                    null
                } else {
                    runCatching { source.resolver.openInputStream(source.uri) }.getOrNull()
                }
            }
            is DocumentImportSource.FileDescriptor -> {
                // Dup so our read closes only the duplicate; the caller's original fd survives,
                // honouring the "importer does not close the source PFD" ownership contract.
                val dup = runCatching { source.pfd.dup() }.getOrNull()
                dup?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
            }
        }

    private fun drainBounded(input: InputStream, maxBytes: Long): ByteArray? {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L
        // One extra byte beyond maxBytes so the chosen backend can still observe an over-cap
        // file (size > maxBytes) and reject it, without this importer ever buffering the whole
        // oversized file.
        val ceiling = maxBytes + 1
        while (total < ceiling) {
            val want = minOf(buf.size.toLong(), ceiling - total).toInt()
            val n = runCatching { input.read(buf, 0, want) }.getOrElse { return null }
            if (n < 0) break
            baos.write(buf, 0, n)
            total += n
        }
        return baos.toByteArray()
    }

    /**
     * Outcome of the image-decode seam: the decode + thumbnail-encode collapsed into a small
     * value so the orchestrator never touches `SharedMemory`. That keeps the SharedMemory
     * lifetime (map / unmap / close) and the hostile-image raster handling inside the seam,
     * and lets unit tests fake the seam with plain bytes.
     */
    internal sealed interface ImageDecodeOutcome {
        data class Decoded(
            val thumbnailBytes: ByteArray,
            val widthPx: Int,
            val heightPx: Int,
        ) : ImageDecodeOutcome

        data class Rejected(val kind: ImageDecodeRejectedKind) : ImageDecodeOutcome
    }

    /**
     * A confirmed barcode to persist as a composite — the pure `(payload, format)` distilled from
     * the isolated [BarcodeDecodeResult] once the consumer's confirm gate passed. Internal so the
     * importer never threads a raw [BarcodeDecodeResult] (with its reject arms) past the seam.
     */
    internal data class DecodedBarcode(
        val payload: String,
        val format: ScannableFormat,
    )

    internal companion object {
        // Read buffer for the bounded materialization loop. 64 KiB matches the
        // InputStream.copyTo default and keeps the read syscall count low.
        const val COPY_BUFFER_SIZE: Int = 64 * 1024

        // Cosmetic memfd label for the import PFD (visible in /proc/<pid>/fd).
        const val MEMFD_DEBUG_NAME: String = "walt-document-import"

        /**
         * Builds the production importer: the seams materialize the buffered bytes into a
         * sealed in-RAM `memfd` PFD and bind the isolated backends. This glue is the only part
         * not exercised by the JVM orchestration tests; the on-device suite covers it.
         */
        internal fun create(context: Context, config: DocumentImportConfig): DocumentImporter {
            val appContext = context.applicationContext
            val pfdFactory = MemfdPfdFactory(MEMFD_DEBUG_NAME)
            val pdfImporter = PdfImporter.create(appContext, config.pdfConfig)
            val imageDecoder = BoundedImageDecoder.create(appContext)
            val barcodeDecoder = BarcodeImageDecoder.create(appContext)
            return DefaultDocumentImporter(
                config = config,
                pdfImport = { bytes, label, persist ->
                    val pfd = pfdFactory.fromBytes(bytes)
                    try {
                        pdfImporter.import(PdfImportSource.FileDescriptor(pfd), label, persist)
                    } finally {
                        runCatching { pfd.close() }
                    }
                },
                imageDecode = { bytes, maxPx ->
                    val pfd = pfdFactory.fromBytes(bytes)
                    try {
                        when (val r = imageDecoder.decode(ImageSource.FileDescriptor(pfd), maxPx, maxPx)) {
                            is ImageDecodeResult.Rejected -> ImageDecodeOutcome.Rejected(r.kind)
                            is ImageDecodeResult.Ok -> encodeRasterToThumbnail(r)
                        }
                    } finally {
                        runCatching { pfd.close() }
                    }
                },
                barcodeExtract = { bytes ->
                    // A second memfd over the same bytes — the image-decode and barcode-extract
                    // sandboxes each consume their own fd; the source was still read exactly once.
                    val pfd = pfdFactory.fromBytes(bytes)
                    try {
                        barcodeDecoder.decode(BarcodeImageSource.FileDescriptor(pfd))
                    } finally {
                        runCatching { pfd.close() }
                    }
                },
                now = { SystemClock.elapsedRealtime() },
                wallClock = { System.currentTimeMillis() },
                idGenerator = { UUID.randomUUID().toString() },
            )
        }

        /**
         * Reconstructs the bounded ARGB_8888 raster the sandbox returned into a host `Bitmap`,
         * PNG-encodes it as the display thumbnail, and closes the `SharedMemory` on every path.
         * The raster is Walt-produced (already decoded and bounded inside the sandbox), so this
         * in-process work never runs a codec over hostile bytes. Returns
         * [ImageDecodeOutcome.Rejected] with [ImageDecodeRejectedKind.DecodeFailed] if the
         * reconstruction or PNG-compress throws.
         */
        private fun encodeRasterToThumbnail(ok: ImageDecodeResult.Ok): ImageDecodeOutcome =
            runCatching {
                val mapped = ok.sharedMemory.mapReadOnly()
                try {
                    val bitmap = Bitmap.createBitmap(ok.widthPx, ok.heightPx, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.copyPixelsFromBuffer(mapped)
                        val png = ByteArrayOutputStream().also { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                        }.toByteArray()
                        ImageDecodeOutcome.Decoded(png, ok.widthPx, ok.heightPx)
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    SharedMemory.unmap(mapped)
                    ok.sharedMemory.close()
                }
            }.getOrElse {
                runCatching { ok.sharedMemory.close() }
                ImageDecodeOutcome.Rejected(ImageDecodeRejectedKind.DecodeFailed)
            }

        private const val PNG_QUALITY: Int = 100
    }
}
