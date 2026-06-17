package `is`.walt.passes.document

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.os.SystemClock
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
internal class DefaultDocumentImporter(
    private val config: DocumentImportConfig,
    private val pdfImport: suspend (ByteArray, String, PdfPersist) -> PdfImportResult,
    private val imageDecode: suspend (ByteArray, Int) -> ImageDecodeOutcome,
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
        persist: suspend (DocumentPersist) -> Unit,
    ): DocumentImportResult {
        val bytes = readBounded(source) ?: return DocumentImportResult.Unrecognized
        return when {
            isPdfHeader(bytes) -> importPdf(bytes, displayLabel, persist)
            else -> {
                val format = sniffImageFormat(bytes)
                if (format != null) {
                    importImage(bytes, format, displayLabel, persist)
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

        runCatching {
            persist(
                DocumentPersist.Image(
                    label = displayLabel,
                    bytes = bytes,
                    thumbnailBytes = decoded.thumbnailBytes,
                    format = format,
                    widthPx = decoded.widthPx,
                    heightPx = decoded.heightPx,
                ),
            )
        }.getOrElse { t ->
            if (t is CancellationException) throw t
            guard.onImportFailed(
                ImageImportFailedEvent(ImageImportFailureKind.StorageHandoff, now() - startedAt),
            )
            return DocumentImportResult.StorageHandoffFailed
        }

        val doc = ImageDocument(
            id = ImageDocumentId(idGenerator()),
            displayLabel = displayLabel,
            byteCount = bytes.size.toLong(),
            widthPx = decoded.widthPx,
            heightPx = decoded.heightPx,
            importedAtEpochMs = wallClock(),
        )
        guard.onImportSucceeded(
            ImageImportSucceededEvent(
                byteCount = doc.byteCount,
                format = format,
                widthPx = doc.widthPx,
                heightPx = doc.heightPx,
                durationMillis = now() - startedAt,
            ),
        )
        return DocumentImportResult.ImportedImage(doc)
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
