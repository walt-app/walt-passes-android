package `is`.walt.passes.pdf.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import `is`.walt.passes.pdf.DocumentRejectedKind
import `is`.walt.passes.pdf.DocumentTelemetryGuard
import `is`.walt.passes.pdf.PdfDocument
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.pdf.android.RenderResult
import `is`.walt.passes.pdf.android.RenderSourceRect
import `is`.walt.passes.pdf.ui.internal.RenderedPageCache
import `is`.walt.passes.pdf.ui.internal.decodePage
import `is`.walt.passes.pdf.ui.internal.renderOrDiscard

/**
 * Outcome of a [rememberPdfThumbnail] call. Drives consumer placeholder / bitmap /
 * error chrome from a single sealed state.
 *
 * The shape is deliberately narrow: only an `ImageBitmap`, an aspect ratio, and an
 * enum failure kind. There is no field through which a consumer could surface PDF
 * text, metadata, an annotation list, or any other extraction-shaped data; ADR 0005
 * D4 (no extraction) and D5 (no signature affordance) are upheld by the type itself.
 * `PdfThumbnailSurfaceTest` locks the arm list and the per-arm field shape against
 * future additions.
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
 * Bounded shared cache for page bitmaps produced by [rememberPdfThumbnail]. A single
 * instance hoisted to list scope (e.g. `remember { PdfThumbnailCache(8) }` inside a
 * `LazyColumn`'s composable parent) lets all visible rows share a fixed RAM cap and
 * keeps recently-scrolled rows warm.
 *
 * Wraps the kernel's access-ordered LRU and binds the `Bitmap.recycle` eviction
 * callback so native pixel memory is freed the moment a page falls out of the
 * window — the renderer service has already pre-recycled its source-side copy on
 * the binder boundary, so the UI consumer is the sole owner on this side.
 *
 * Thread-safety: callers are expected to invoke this from the Compose main thread.
 * Background prefetch is not supported by this surface.
 */
public class PdfThumbnailCache(maxSize: Int) {
    internal val backing: RenderedPageCache<Bitmap> = RenderedPageCache(
        maxSize = maxSize,
        onEvict = { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() },
    )

    public val size: Int get() = backing.size

    public fun clear(): Unit = backing.clear()
}

/**
 * Compose-friendly facade over the isolated-process PDF renderer for **single-page
 * thumbnails**. Use this from a list-row composable to drive an asynchronously
 * rendered page-N bitmap with correct lifetime, cancellation, and cache discipline
 * for the trust-bearing renderer service.
 *
 * Lifecycle guarantees this facade owns so consumers do not have to reimplement them:
 *
 *  - `renderer.render(...)` runs inside `NonCancellable` (via `renderOrDiscard`),
 *    so a fast scroll that disposes the row mid-render still receives the
 *    `RenderResult` and releases the result's `SharedMemory` region — preserving
 *    the wpass-6ag N1 invariant.
 *  - When [cache] is supplied, the produced bitmap is handed to the cache for the
 *    `(document.id, page)` key; the LRU evicts older entries with `Bitmap.recycle`.
 *  - When [cache] is omitted, the bitmap is recycled on dispose of the composable
 *    (key change, scroll-off, leave composition).
 *
 * Trust-bearing properties this facade preserves (ADR 0005):
 *
 *  - **D3 (isolated process):** the [renderer] is the caller's already-bound binder;
 *    this facade does not open its own service connection.
 *  - **D4 (no extraction):** the facade body invokes only `render(...)`; no
 *    `getText` / `getMetadata` / `getAnnotations` paths are added or wrapped. The
 *    return type exposes a single `ImageBitmap` + aspect float.
 *  - **D5 (no signature affordance):** there is no "verified" field on
 *    [PdfThumbnailState]. Trust signals remain on `DocumentTrustCaption` and
 *    `DocumentView` chrome, untouched.
 *  - **D7 (4 MP cap):** the cap is enforced inside `PdfRendererService`; this
 *    facade passes [targetSizePx] through. Oversized requests surface as
 *    `Failed(RendererFailed)`.
 *
 * pdfFile lifetime: [pdfFile] is owned by the caller. It MUST remain open for as
 * long as the calling composable is composed; close after composition ends. The
 * binder duplicates the fd on each transact, so closing on the caller side does
 * not affect an in-flight render's copy, but a closed fd in a subsequent call
 * surfaces as `Failed(RendererFailed)` rather than a UI-visible error.
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
        // Tracked only when there's no cache to take ownership; the cache's onEvict
        // recycles bitmaps it owns on its own schedule.
        var ownedHandle: Bitmap? = null

        val cached = cache?.backing?.get(document.id, page)
        if (cached != null && !cached.isRecycled) {
            val aspect = cached.width.toFloat() / cached.height.coerceAtLeast(1).toFloat()
            value = PdfThumbnailState.Rendered(cached.asImageBitmap(), aspect)
        } else {
            val result = renderOrDiscard(
                renderer = renderer,
                pdf = pdfFile,
                page = page,
                widthPx = widthPx,
                heightPx = heightPx,
                sourceRect = RenderSourceRect.FullPage,
                isStillWanted = { value is PdfThumbnailState.Loading },
            )
            when (result) {
                null -> Unit // discarded; SharedMemory already closed inside renderOrDiscard
                is RenderResult.Ok -> {
                    val decoded = decodePage(result, telemetry)
                    if (decoded != null) {
                        if (cache != null) {
                            cache.backing.put(document.id, page, decoded.bitmap)
                        } else {
                            ownedHandle = decoded.bitmap
                        }
                        value = PdfThumbnailState.Rendered(decoded.image, decoded.pageAspect)
                    } else {
                        // decodePage already routed the cause to telemetry; surface a
                        // user-actionable failure shape so the row can render a
                        // placeholder rather than spin forever.
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
