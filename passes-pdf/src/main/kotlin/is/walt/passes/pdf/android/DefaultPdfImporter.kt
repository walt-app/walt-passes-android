package `is`.walt.passes.pdf.android

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import `is`.walt.passes.pdf.DocumentImportFailedEvent
import `is`.walt.passes.pdf.DocumentImportSucceededEvent
import `is`.walt.passes.pdf.DocumentRejectedKind
import `is`.walt.passes.pdf.PdfDocument
import `is`.walt.passes.pdf.PdfDocumentId
import `is`.walt.passes.pdf.PdfImportConfig
import `is`.walt.passes.pdf.PdfImportResult
import `is`.walt.passes.pdf.android.internal.AndroidRendererSessionFactory
import `is`.walt.passes.pdf.android.internal.MemfdPfdFactory
import `is`.walt.passes.pdf.android.internal.PdfPfdFactory
import `is`.walt.passes.pdf.android.internal.PngThumbnailEncoder
import `is`.walt.passes.pdf.android.internal.RendererSessionFactory
import `is`.walt.passes.pdf.android.internal.ThumbnailEncoder
import `is`.walt.passes.pdf.isPdfHeader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * The single import entry point honouring ADR 0005 G.1's "API-34-or-reject" gate and
 * the F.1 memfd discipline. The orchestration sequence is the load-bearing contract:
 *
 *  1. SDK gate (G.1) — before *any* source byte is read.
 *  2. Materialize the source to a bounded, in-memory byte buffer with a fail-fast cap.
 *  3. Header-sniff the first 8 bytes (D4 cover) — before binding the renderer service.
 *  4. Memfd-allocate a fresh [ParcelFileDescriptor] (F.1) — no plaintext on disk.
 *  5. Bind the renderer service, await connection, wrap the binder.
 *  6. Probe → render(page=0) for a smoke thumbnail.
 *  7. Reconstruct the bitmap from SharedMemory, encode as PNG.
 *  8. Hand off to the consumer's `persist` lambda.
 *
 * Each step's rejection routes back into the importer's [DocumentRejectedKind] enum;
 * the renderer service is unbound in a `finally` regardless of outcome.
 *
 * Rejection-arm routing keeps trust bands distinct:
 *
 *  - [DocumentRejectedKind.RendererFailed] is reserved for the bind→probe→render window
 *    (including pfd-alloc and source-fd dup failures, which are part of preparing the
 *    renderer handoff). A spike here is the signal that PDFium may have refused a file.
 *  - [DocumentRejectedKind.EncoderFailed] covers post-render PNG encoding failures
 *    (SharedMemory map / bitmap reconstruction / PNG compress). A spike here is "the
 *    renderer succeeded but our PNG path blew up."
 *  - [DocumentRejectedKind.StorageHandoffFailed] is reserved for `persist` throws.
 *    A spike here points the on-call at the consumer's storage layer, not the renderer.
 *
 * [CancellationException] is rethrown from the encode and persist wrap points so a
 * parent-scope cancel during import surfaces as cancellation, preserving structured
 * concurrency instead of silently converting cancellation into an import rejection.
 *
 * Test seams are kept internal — the public entry remains the [PdfImporter.create]
 * factory. Unit tests in this module construct [DefaultPdfImporter] directly with fake
 * factories so the orchestration can be exercised without a live binder or
 * `Os.memfd_create`. Adding a third seam in the consumer would re-open the
 * parallel-implementation gap the importer exists to close.
 */
internal class DefaultPdfImporter(
    private val context: Context,
    private val config: PdfImportConfig,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val deps: Deps = Deps(),
) : PdfImporter {
    /**
     * Internal seams folded into one record so the constructor stays under detekt's
     * parameter cap. Every field is independently overridable from tests; production
     * callers never construct [Deps] directly because the public [PdfImporter.create]
     * factory builds [DefaultPdfImporter] with the production-default [Deps].
     */
    internal data class Deps(
        val pfdFactory: PdfPfdFactory = MemfdPfdFactory(),
        val sessionFactoryFor: (Context) -> RendererSessionFactory = ::AndroidRendererSessionFactory,
        val thumbnailEncoder: ThumbnailEncoder = PngThumbnailEncoder,
        val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
        val idGenerator: () -> String = { UUID.randomUUID().toString() },
    )

    private val sessionFactory: RendererSessionFactory by lazy { deps.sessionFactoryFor(context) }

    /**
     * `@Suppress("ReturnCount")` matches the precedent set by `DefaultPassParser.runPipeline`
     * in passes-core: each stage (SDK gate, materialize, pfd alloc, render+persist) is its
     * own short-circuit, and collapsing the four returns into two either re-introduces a
     * helper-chain or hides the early-exit shape behind monadic plumbing whose payoff
     * does not justify the indirection at this size.
     */
    @Suppress("ReturnCount")
    override suspend fun import(
        source: PdfImportSource,
        displayLabel: String,
        persist: suspend (label: String, pdfBytes: ByteArray, pageCount: Int, thumbnailBytes: ByteArray) -> Unit,
    ): PdfImportResult {
        val startedAt = deps.now()
        if (sdkInt < ANDROID_14_API) {
            return rejectAndReport(DocumentRejectedKind.UnsupportedAndroidVersion, startedAt)
        }
        config.telemetryGuard.onImportStarted()
        val bytes =
            when (val m = materialize(source, startedAt)) {
                is Materialized.Ok -> m.bytes
                is Materialized.Reject -> return m.result
            }
        val pfd =
            when (val a = allocatePfd(bytes, startedAt)) {
                is PfdAlloc.Ok -> a.pfd
                is PfdAlloc.Reject -> return a.result
            }
        return try {
            renderAndPersist(pfd, bytes, displayLabel, persist, startedAt)
        } finally {
            runCatching { pfd.close() }
        }
    }

    @Suppress("ReturnCount")
    private fun materialize(source: PdfImportSource, startedAt: Long): Materialized {
        val bytes =
            when (val r = readBounded(source, config.maxBytes)) {
                is BoundedRead.Bytes -> r.bytes
                BoundedRead.Oversized ->
                    return Materialized.Reject(rejectAndReport(DocumentRejectedKind.OversizedAtImport, startedAt))
                BoundedRead.SourceUnavailable ->
                    return Materialized.Reject(rejectAndReport(DocumentRejectedKind.NotAPdf, startedAt))
            }
        if (bytes.size < HEADER_BYTES || !isPdfHeader(bytes.copyOfRange(0, HEADER_BYTES))) {
            return Materialized.Reject(rejectAndReport(DocumentRejectedKind.NotAPdf, startedAt))
        }
        return Materialized.Ok(bytes)
    }

    private fun allocatePfd(bytes: ByteArray, startedAt: Long): PfdAlloc =
        runCatching { deps.pfdFactory.fromBytes(bytes) }
            .fold(
                onSuccess = { PfdAlloc.Ok(it) },
                onFailure = { PfdAlloc.Reject(rejectAndReport(DocumentRejectedKind.RendererFailed, startedAt)) },
            )

    private sealed interface Materialized {
        class Ok(val bytes: ByteArray) : Materialized

        class Reject(val result: PdfImportResult.Rejected) : Materialized
    }

    private sealed interface PfdAlloc {
        class Ok(val pfd: ParcelFileDescriptor) : PfdAlloc

        class Reject(val result: PdfImportResult.Rejected) : PfdAlloc
    }

    private suspend fun renderAndPersist(
        pfd: ParcelFileDescriptor,
        bytes: ByteArray,
        displayLabel: String,
        persist: suspend (String, ByteArray, Int, ByteArray) -> Unit,
        startedAt: Long,
    ): PdfImportResult =
        sessionFactory.connect().use { session ->
            val pages =
                when (val probe = session.client.probe(pfd)) {
                    is ProbeResult.Rejected -> return@use rejectAndReport(probe.kind, startedAt)
                    is ProbeResult.Ok -> probe.pageCount
                }
            val thumbnailBytes =
                when (
                    val render = session.client.render(
                        pdf = pfd,
                        page = 0,
                        widthPx = THUMB_WIDTH_PX,
                        heightPx = THUMB_HEIGHT_PX,
                    )
                ) {
                    is RenderResult.Rejected -> return@use rejectAndReport(render.kind, startedAt)
                    is RenderResult.Ok ->
                        runCatching { deps.thumbnailEncoder.encode(render) }
                            .getOrElse { t ->
                                // CancellationException is part of structured concurrency: catching
                                // it here would silently convert a parent-scope cancel into an
                                // EncoderFailed rejection, breaking the parent's "import was
                                // cancelled" signal. Rethrow lets the cancellation propagate.
                                if (t is CancellationException) throw t
                                return@use rejectAndReport(DocumentRejectedKind.EncoderFailed, startedAt)
                            }
                }
            // Persist failure routes to StorageHandoffFailed (distinct from RendererFailed):
            // the consumer's storage layer threw, not the isolated renderer. Splitting the
            // arms gives the on-call a clean "SQLCipher wedged" vs "PDFium choked" signal.
            // CancellationException is rethrown so a parent-scope cancel mid-persist
            // surfaces as cancellation, not as an Importer rejection.
            runCatching { persist(displayLabel, bytes, pages, thumbnailBytes) }
                .getOrElse { t ->
                    if (t is CancellationException) throw t
                    return@use rejectAndReport(DocumentRejectedKind.StorageHandoffFailed, startedAt)
                }
            val doc =
                PdfDocument(
                    id = PdfDocumentId(deps.idGenerator()),
                    displayLabel = displayLabel,
                    byteCount = bytes.size.toLong(),
                    pageCount = pages,
                    importedAtEpochMs = System.currentTimeMillis(),
                )
            config.telemetryGuard.onImportSucceeded(
                DocumentImportSucceededEvent(
                    byteCount = doc.byteCount,
                    pageCount = doc.pageCount,
                    durationMillis = deps.now() - startedAt,
                ),
            )
            PdfImportResult.Imported(doc)
        }

    private fun rejectAndReport(
        kind: DocumentRejectedKind,
        startedAt: Long,
    ): PdfImportResult.Rejected {
        config.telemetryGuard.onImportFailed(
            DocumentImportFailedEvent(outcome = kind, durationMillis = deps.now() - startedAt),
        )
        return PdfImportResult.Rejected(kind)
    }

    private fun readBounded(source: PdfImportSource, maxBytes: Long): BoundedRead {
        val stream = openSource(source) ?: return BoundedRead.SourceUnavailable
        return stream.use { drainBounded(it, maxBytes) }
    }

    private fun openSource(source: PdfImportSource): InputStream? =
        when (source) {
            is PdfImportSource.ContentUri -> {
                // Scheme allowlist: ContentResolver.openInputStream will happily resolve a
                // `file://` URI to an arbitrary filesystem path, which is exactly the
                // path-arm escape hatch the sealed PdfImportSource shape was supposed to
                // close. Refusing non-`content://` schemes here keeps the implementation
                // aligned with the documented threat model on PdfImportSource.
                if (source.uri.scheme != ContentResolver.SCHEME_CONTENT) {
                    null
                } else {
                    runCatching { source.resolver.openInputStream(source.uri) }.getOrNull()
                }
            }
            is PdfImportSource.FileDescriptor -> {
                // Dup the caller's PFD so AutoCloseInputStream's `close()` releases only
                // our duplicate; the caller's original fd survives, honouring the
                // "importer does not close the source PFD" ownership contract on
                // PdfImportSource.
                val dup = runCatching { source.pfd.dup() }.getOrNull()
                dup?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
            }
        }

    private fun drainBounded(input: InputStream, maxBytes: Long): BoundedRead {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L
        // One extra byte beyond maxBytes so the loop distinguishes "exactly at cap" from
        // "over". The renderer never sees more bytes than what we ultimately hand back.
        val ceiling = maxBytes + 1
        while (total < ceiling) {
            val want = minOf(buf.size.toLong(), ceiling - total).toInt()
            val n =
                runCatching { input.read(buf, 0, want) }
                    .getOrElse { return BoundedRead.SourceUnavailable }
            if (n < 0) break
            baos.write(buf, 0, n)
            total += n
        }
        return if (total > maxBytes) BoundedRead.Oversized else BoundedRead.Bytes(baos.toByteArray())
    }

    private sealed interface BoundedRead {
        data class Bytes(val bytes: ByteArray) : BoundedRead

        data object Oversized : BoundedRead

        data object SourceUnavailable : BoundedRead
    }

    internal companion object {
        // Read buffer for the materialization loop. 64 KiB matches the InputStream.copyTo
        // default and keeps the read syscall count low without inflating per-import RAM.
        const val COPY_BUFFER_SIZE: Int = 64 * 1024

        // Thumbnail dimensions for the smoke render. 600 x 800 = 480 000 px, comfortably
        // below the renderer service's 4 MP cap (PdfRendererService.MAX_PIXELS), and an
        // aspect ratio close enough to the 4:3 tile that DocumentTile renders into that
        // the bitmap's intrinsic shape doesn't fight the layout's aspect-ratio constraint.
        const val THUMB_WIDTH_PX: Int = 600
        const val THUMB_HEIGHT_PX: Int = 800

        const val HEADER_BYTES: Int = 8

        // ADR 0005 G.1 floor.
        const val ANDROID_14_API: Int = 34
    }
}
