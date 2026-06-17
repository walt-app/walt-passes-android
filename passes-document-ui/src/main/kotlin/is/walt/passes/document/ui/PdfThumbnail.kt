package `is`.walt.passes.document.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import `is`.walt.passes.document.DocumentRejectedKind
import `is`.walt.passes.document.DocumentTelemetryGuard
import `is`.walt.passes.document.PdfDocument
import `is`.walt.passes.document.PdfDocumentId
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.pdf.android.RenderResult
import `is`.walt.passes.pdf.android.RenderSourceRect
import `is`.walt.passes.document.ui.internal.RenderedPageCache
import `is`.walt.passes.document.ui.internal.decodePage
import `is`.walt.passes.document.ui.internal.renderOrDiscard
import kotlinx.coroutines.isActive

/**
 * Outcome of a [rememberPdfThumbnail] call. Drives consumer placeholder / bitmap /
 * error chrome from a single sealed state. The shape is narrow by design: no field
 * through which a consumer could surface PDF text, metadata, or annotations. ADR
 * 0005 D4 is upheld by the type itself; `PdfThumbnailSurfaceTest` locks the arms.
 */
public sealed interface PdfThumbnailState {
    public data object Loading : PdfThumbnailState

    public data class Rendered(
        public val image: ImageBitmap,
        public val pageAspect: Float,
    ) : PdfThumbnailState

    public data class Failed(public val kind: DocumentRejectedKind) : PdfThumbnailState
}

/**
 * The cache's default size: how many recently-rendered pages to retain per consumer.
 * Sized so the `HorizontalPager` in `DocumentView` can keep the current page plus
 * `±2` adjacent pages hot during a swipe without recycling a bitmap that is still
 * being painted.
 */
public const val DEFAULT_PAGE_WINDOW: Int = 5

/**
 * Bounded shared cache for page bitmaps produced by [rememberPdfThumbnail]. Hoist a
 * single instance to list scope (e.g. `remember { PdfThumbnailCache() }` inside a
 * `LazyColumn`'s parent) so all visible rows share a fixed RAM cap.
 *
 * Binds `Bitmap.recycle` as the eviction callback on top of the kernel's
 * access-ordered LRU; see `RenderedPageCache` for the cache semantics this wraps.
 * Callers must invoke this from the Compose main thread.
 */
public class PdfThumbnailCache(maxSize: Int = DEFAULT_PAGE_WINDOW) {
    private val backing: RenderedPageCache<CachedPage> = RenderedPageCache(
        maxSize = maxSize,
        onEvict = { entry -> if (!entry.bitmap.isRecycled) entry.bitmap.recycle() },
    )

    public fun clear(): Unit = backing.clear()

    internal fun get(documentId: PdfDocumentId, page: Int): CachedPage? =
        backing.get(documentId, page)

    internal fun put(documentId: PdfDocumentId, page: Int, value: CachedPage): Unit =
        backing.put(documentId, page, value)
}

/**
 * Per-entry payload: the rasterised bitmap and the source page's natural aspect
 * ratio. Caching `pageAspect` alongside the bitmap keeps cache-hit and cold-render
 * paths visually identical (bitmap dimensions can drift from source aspect when
 * the renderer downsizes against the 4 MP cap).
 */
internal data class CachedPage(val bitmap: Bitmap, val pageAspect: Float)

/**
 * Compose-friendly facade over the isolated-process PDF renderer for **single-page
 * thumbnails**. Use this from a list-row composable to drive an asynchronously
 * rendered page-N bitmap with correct lifetime, cancellation, and cache discipline.
 *
 * Lifecycle guarantees this facade owns so consumers do not have to reimplement:
 *
 *  - `renderer.render(...)` runs inside `NonCancellable` (via `renderOrDiscard`),
 *    so a row disposed mid-render still receives the `RenderResult` and releases
 *    its `SharedMemory` region.
 *  - When [cache] is supplied, the bitmap is handed to the cache; the LRU evicts
 *    older entries with `Bitmap.recycle`. When [cache] is omitted, the bitmap is
 *    recycled on dispose of the composable.
 *
 * Trust posture: see ADR 0005 D4 — this facade preserves D3 (consumes the
 * caller's binder, does not open a new service connection), D4 (no extraction
 * surface in body or return type), D5 (no signature affordance), and D7 (passes
 * [targetSizePx] through to the renderer's 4 MP cap; oversized requests surface
 * as `Failed(RendererFailed)`).
 *
 * [pdfFile] is owned by the caller and must remain open while the composable is
 * composed; close after composition ends.
 */
@Composable
@Suppress("LongParameterList")
public fun rememberPdfThumbnail(
    document: PdfDocument,
    pdfFile: ParcelFileDescriptor,
    renderer: PdfRendererBinder,
    targetSizePx: IntSize,
    page: Int = 0,
    telemetry: DocumentTelemetryGuard = DocumentTelemetryGuard.NoOp,
    cache: PdfThumbnailCache? = null,
): PdfThumbnailState {
    val widthPx = targetSizePx.width.coerceAtLeast(1)
    val heightPx = targetSizePx.height.coerceAtLeast(1)
    val state: State<PdfThumbnailState> = produceState<PdfThumbnailState>(
        initialValue = PdfThumbnailState.Loading,
        document.id,
        page,
        widthPx,
        heightPx,
        cache,
        renderer,
        pdfFile,
    ) {
        // produceState reuses a shared MutableState across producer restarts, so
        // gating staleness on `value` is unsafe — a newer producer may have already
        // mutated it. The producer scope's isActive flips when the old job is
        // cancelled by a key change, which is the correct liveness signal.
        val producerScope = this
        // Tracked only when there's no cache to take ownership; the cache's onEvict
        // recycles bitmaps it owns on its own schedule.
        var ownedHandle: Bitmap? = null

        val cached = cache?.get(document.id, page)
        if (cached != null && !cached.bitmap.isRecycled) {
            value = PdfThumbnailState.Rendered(cached.bitmap.asImageBitmap(), cached.pageAspect)
        } else {
            val result = renderOrDiscard(
                renderer = renderer,
                pdf = pdfFile,
                page = page,
                widthPx = widthPx,
                heightPx = heightPx,
                sourceRect = RenderSourceRect.FullPage,
                isStillWanted = { producerScope.isActive },
            )
            when (result) {
                null -> Unit // discarded; SharedMemory already closed inside renderOrDiscard
                is RenderResult.Ok -> {
                    val decoded = decodePage(result, telemetry)
                    if (decoded != null) {
                        if (cache != null) {
                            cache.put(document.id, page, CachedPage(decoded.bitmap, decoded.pageAspect))
                        } else {
                            ownedHandle = decoded.bitmap
                        }
                        value = PdfThumbnailState.Rendered(decoded.image, decoded.pageAspect)
                    } else {
                        // decodePage routed the cause to telemetry; surface a
                        // user-actionable shape so the row can render a placeholder
                        // rather than spin forever.
                        value = PdfThumbnailState.Failed(DocumentRejectedKind.RendererFailed)
                    }
                }
                is RenderResult.Rejected -> {
                    value = PdfThumbnailState.Failed(result.kind)
                }
            }
        }
        awaitDispose {
            ownedHandle?.let { if (!it.isRecycled) it.recycle() }
        }
    }
    return state.value
}
