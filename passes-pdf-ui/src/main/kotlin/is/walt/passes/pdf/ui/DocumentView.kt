package `is`.walt.passes.pdf.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import `is`.walt.passes.pdf.ConsumerRenderFailure
import `is`.walt.passes.pdf.DocumentTelemetryGuard
import `is`.walt.passes.pdf.PdfDocument
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.pdf.android.RenderResult
import `is`.walt.passes.pdf.ui.internal.RenderedPageCache
import `is`.walt.passes.pdf.ui.theme.LocalDocumentSemantics
import `is`.walt.passes.ui.core.toComposeColor
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * Presentation of a [PdfDocument] — a non-suppressible trust caption above a swipeable
 * pager of rasterised pages. `DocumentView` fills the bounds the consumer gives it and
 * does NOT assume a full screen: the caption takes its natural height, the pager takes
 * the rest, and each page is letterboxed into the pager slot rather than sized from a
 * fixed aspect ratio — so a short consumer slot can never make a page overflow upward
 * into the caption. The trust caption is composed on the consumer's own background;
 * only the pager carries [is.walt.passes.pdf.ui.theme.DocumentSemantics.laneBackground],
 * so the caption reads as host screen chrome rather than part of the document surface.
 * Pages are rasterised on demand by the isolated-process renderer
 * reached through [renderer] (the `PdfRendererBinder` interface, never the concrete
 * `PdfRendererClient`, so test fakes substitute cleanly) and held in a small
 * per-document LRU cache to amortise re-render on quick swipes.
 *
 * Trust contract:
 *
 *  - The non-suppressible [DocumentTrustCaption] is rendered inside this view and is
 *    not gated by any parameter. There is no `DocumentView` overload that omits it.
 *    `DocumentSurfaceLockTest` pins the parameter shape; `DocumentTrustSurfaceTest`
 *    pins the visible-text contract.
 *  - The view displays only the rasterised page bitmaps and the caption. ADR 0005 D4:
 *    no PDF metadata, no extracted text, no annotation list, no attachment list.
 *  - The view exposes no share, export, print, or open-with affordance. ADR 0005 D8.
 *    `DocumentPublicApiSurfaceTest.passesPdfUiCompiledClassesContainNoForbiddenStrings`
 *    enforces by scanning compiled bytecode for `android.intent.action.SEND` and the
 *    `application/pdf` MIME literal so a future contributor cannot quietly add either.
 *
 * Bitmap ownership: the LRU cache stores native [Bitmap]s and recycles them on
 * eviction; the renderer service has already pre-recycled its source-side copy
 * before returning. On dispose the cache is cleared so every retained pixel buffer
 * is freed at the same moment the composable leaves composition.
 *
 * Cancellation: [renderer]'s suspend functions are cancellation-cooperative — the
 * `LaunchedEffect` keyed on the current page is cancelled when the user swipes,
 * freeing the rendering coroutine even if the underlying binder transact is still
 * in flight on its IO worker.
 *
 * pdfFile lifetime: [pdfFile] is owned by the caller. It MUST remain open for as
 * long as `DocumentView` is composed; closing it earlier produces undefined
 * behaviour for any in-flight `render()` call (the binder duplicates the fd
 * on each transact, but a closed source fd surfaces as a renderer-side
 * `RendererFailed`, not as a UI-visible error). Close after `DocumentView` leaves
 * composition.
 */
@Composable
public fun DocumentView(
    doc: PdfDocument,
    pdfFile: ParcelFileDescriptor,
    renderer: PdfRendererBinder,
    modifier: Modifier = Modifier,
    telemetry: DocumentTelemetryGuard = DocumentTelemetryGuard.NoOp,
) {
    val semantics = LocalDocumentSemantics.current
    val cache = remember(doc.id) {
        RenderedPageCache<Bitmap>(
            maxSize = LRU_PAGE_WINDOW,
            onEvict = { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            },
        )
    }
    DisposableEffect(doc.id) {
        onDispose { cache.clear() }
    }

    val pagerState = rememberPagerState(pageCount = { doc.pageCount })

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The caption is composed directly on whatever background the consumer puts
        // behind DocumentView — it deliberately does NOT sit on `laneBackground`. The
        // trust caption reads as host screen chrome; only the pager below carries the
        // document-surface tone.
        DocumentTrustCaption()

        // pageCount = 0 is rejected at import (DocumentRejectedKind.RendererFailed),
        // so the pager renders nothing. HorizontalPager handles a 0-count state
        // gracefully without a placeholder; a custom branch here would only mask a
        // future regression in the import path with a silent empty surface.
        //
        // `laneBackground` is painted here, behind the pager only — it is the
        // document-surface tone the rasterised page sits on (and shows through the
        // ContentScale.Fit letterbox bars). `.background` before `.padding` so the tone
        // fills the whole pager slot edge-to-edge and the padding insets only the page
        // content within it.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(semantics.laneBackground.toComposeColor())
                .padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
        ) { page ->
            DocumentPage(
                document = doc,
                pageIndex = page,
                pdfFile = pdfFile,
                renderer = renderer,
                cache = cache,
                telemetry = telemetry,
            )
        }
    }
}

/**
 * Each parameter is a distinct dependency the page rendering needs; bundling them
 * into a holder would only relocate the count from a function signature to a
 * constructor without lowering the conceptual surface, so the LongParameterList
 * suppression is the lower-cost choice here.
 */
@Composable
@Suppress("LongParameterList")
private fun DocumentPage(
    document: PdfDocument,
    pageIndex: Int,
    pdfFile: ParcelFileDescriptor,
    renderer: PdfRendererBinder,
    cache: RenderedPageCache<Bitmap>,
    telemetry: DocumentTelemetryGuard,
) {
    val density = LocalDensity.current
    var rendered by remember(document.id, pageIndex) {
        mutableStateOf<ImageBitmap?>(cache.get(document.id, pageIndex)?.asImageBitmap())
    }

    val requestWidthPx = with(density) {
        TARGET_PAGE_WIDTH_DP.dp.toPx().toInt().coerceAtLeast(1)
    }
    val requestHeightPx = with(density) {
        TARGET_PAGE_HEIGHT_DP.dp.toPx().toInt().coerceAtLeast(1)
    }

    LaunchedEffect(document.id, pageIndex, requestWidthPx, requestHeightPx) {
        val cached = cache.get(document.id, pageIndex)
        if (cached != null && !cached.isRecycled) {
            rendered = cached.asImageBitmap()
            return@LaunchedEffect
        }
        val result = renderer.render(pdfFile, pageIndex, requestWidthPx, requestHeightPx)
        if (result is RenderResult.Ok) {
            // ADR 0005 D7: the renderer service caps bitmap area independently and may
            // return a downsized buffer. Use the renderer's reported dimensions, NOT the
            // request, when reconstructing the Bitmap — using the request would make
            // copyPixelsFromBuffer either throw against an undersized SharedMemory
            // (silently swallowed by runCatching below) or render a misshapen image.
            val bitmap = bitmapFromSharedMemory(result, telemetry)
            if (bitmap != null) {
                cache.put(document.id, pageIndex, bitmap)
                rendered = bitmap.asImageBitmap()
            }
        }
    }

    // The page fills the slot the pager hands it (the pager's `weight(1f)` share of
    // DocumentView's Column) and lets ContentScale.Fit letterbox the bitmap inside it.
    // An earlier version sized the page with `fillMaxWidth().aspectRatio(3:4)`, which
    // derives height from width and IGNORES the height it is given: when a consumer
    // hands DocumentView a short slot (e.g. walt-android sandwiches it between a title
    // and a details section), the 3:4 page grew taller than the pager viewport and drew
    // over the trust caption above it. fillMaxSize cannot overflow its slot, so the
    // caption / page boundary is structural regardless of how little height the
    // consumer gives the surface.
    //
    // The wrapping Box is the zoom/pan surface (wpass-1wq). It scopes the gesture and
    // graphicsLayer transform to the current page so swiping to the next page resets the
    // zoom state, and so the trust caption — which lives in DocumentView's Column above
    // the pager — is unaffected by any zoom transform applied here.
    rendered?.let { bitmap ->
        ZoomableDocumentPage(
            bitmap = bitmap,
            contentDescription = "Page ${pageIndex + 1} of ${document.pageCount}",
        )
    }
}

/**
 * Per-page pinch-zoom + pan surface (wpass-1wq). Scoped strictly to a single page slot:
 * the [Box]'s state lives in the caller's `remember(document.id, pageIndex)` frame, so
 * paging away resets `scale` to 1 and `offset` to zero for the next page composition.
 *
 * Gesture priority with the enclosing [HorizontalPager]:
 *
 *  - Two-finger pinch is always consumed here — [transformable] handles it independently
 *    of `canPan`, so the user can zoom out to fit even when the pager owns single-touch.
 *  - Single-touch drag is consumed here only when `scale > 1f` (`canPan = { scale > 1f }`).
 *    At `scale == 1f` the gesture passes through to the pager so horizontal swipes still
 *    advance pages. Once zoomed in, single-touch drags pan the page within the slot until
 *    the user double-taps or pinches back to fit.
 *  - Double-tap toggles between fit (scale = 1, offset = zero) and the canonical zoomed
 *    scale ([DOUBLE_TAP_SCALE]).
 *
 * Translation is clamped so the scaled page cannot be panned entirely off the slot:
 * `maxOffset = ((scale - 1) * slotSize / 2)`, which keeps the page centered at fit and
 * lets the user pan up to the edges of the scaled-up image but no further. Without this
 * clamp the user could fling the barcode out of frame and lose the slot's contents.
 *
 * The graphicsLayer transform applies to the [Image] alone; the trust caption is composed
 * outside this Box (in [DocumentView]'s Column) and is therefore structurally not part of
 * any transform applied here. ADR 0005 D5's non-suppressible-trust-caption contract is
 * preserved by layout, not by gesture-handling discipline.
 *
 * The 4 MP raster the renderer hands us (ADR 0005 D7) is sampled into a fit-resolution
 * bitmap, so it pixelates when scaled above ~1.3-1.5x. The follow-up renderer bead
 * (wpass-f4b) lifts the sharp-at-zoom property by re-rendering the visible viewport at
 * higher resolution within the same 4 MP per-bitmap cap; this gesture surface is correct
 * against the existing bitmap (pixelated but functional) and gets sharper for free once
 * wpass-f4b lands.
 */
@Composable
private fun ZoomableDocumentPage(
    bitmap: ImageBitmap,
    contentDescription: String,
) {
    // Local state, not rememberSaveable: zoom state intentionally does not survive a
    // process death — the page reverts to fit, which matches the user-mental-model of
    // "the wallet just reopened, show me the page at its natural size."
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var slotSize by remember { mutableStateOf(IntSize.Zero) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        val maxX = ((newScale - 1f) * slotSize.width / 2f).coerceAtLeast(0f)
        val maxY = ((newScale - 1f) * slotSize.height / 2f).coerceAtLeast(0f)
        val proposed = offset + panChange
        offset = Offset(
            proposed.x.coerceIn(-maxX, maxX),
            proposed.y.coerceIn(-maxY, maxY),
        )
        scale = newScale
        if (newScale <= MIN_SCALE) offset = Offset.Zero
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { slotSize = it }
            // pointerInput keyed on Unit because the only state the handler reads is the
            // mutable `scale` (re-read on every callback). Re-keying would tear down and
            // recreate the gesture detector on every zoom step.
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > MIN_SCALE) {
                            scale = MIN_SCALE
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_SCALE
                            // Leave offset at zero; the user pans afterward if they need
                            // to bring a specific region to centre. Centring on the tap
                            // point would jump the page under the finger and surprise the
                            // user — see wpass-1wq design notes.
                        }
                    },
                )
            }
            .transformable(state = transformableState, canPan = { scale > MIN_SCALE }),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            // ADR 0005 D4 forbids extracting text from the PDF; positional caption
            // is the only safe TalkBack fallback. "Page 3 of 7" is non-content but
            // navigationally useful.
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}

private fun bitmapFromSharedMemory(
    ok: RenderResult.Ok,
    telemetry: DocumentTelemetryGuard,
): Bitmap? {
    // The renderer service writes ARGB_8888 packed row-major with no padding using the
    // dimensions it reports back in `ok.widthPx` / `ok.heightPx` (which may differ from
    // the request — see the call-site comment for D7).
    //
    // The runCatching swallow remains intentional: OOM on Bitmap.createBitmap,
    // BufferUnderflowException from copyPixelsFromBuffer if the SharedMemory size
    // mismatches the bitmap, and IllegalStateException if the SharedMemory was already
    // closed by a parallel render each surface as a blank page that the next swipe
    // re-attempts. Telemetry is observability, not a user-facing error path; the failure
    // mapping is what wpass-8v4 added so the silent path stops being invisible.
    return runCatching {
        val mapped: ByteBuffer = ok.sharedMemory.mapReadOnly()
        try {
            val bitmap = Bitmap.createBitmap(ok.widthPx, ok.heightPx, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(mapped)
            bitmap
        } finally {
            android.os.SharedMemory.unmap(mapped)
            ok.sharedMemory.close()
        }
    }.onFailure { t ->
        telemetry.onConsumerRenderFailed(consumerRenderFailureFor(t))
    }.getOrNull()
}

internal fun consumerRenderFailureFor(t: Throwable): ConsumerRenderFailure = when (t) {
    is OutOfMemoryError -> ConsumerRenderFailure.OutOfMemory
    is BufferUnderflowException -> ConsumerRenderFailure.DimensionMismatch
    is IllegalStateException -> ConsumerRenderFailure.SharedMemoryUnavailable
    else -> ConsumerRenderFailure.Other
}

// HorizontalPager retains composed Image references for adjacent pages during a swipe
// transition. A window equal to "current ± 2" gives the access-ordered LRU enough room
// that an evicted bitmap is never the one the pager is still painting; recycling a
// bitmap whose Image is on screen crashes the draw pass. See PR review C2.
internal const val LRU_PAGE_WINDOW: Int = 5

// Render budget defaults. The renderer service caps the bitmap area independently
// (ADR 0005 D7); these are the UI-side request size, picked to look reasonable on
// phone-class displays without committing to a particular host density. The on-screen
// page size is the pager slot, not these values — ContentScale.Fit scales the
// rasterised bitmap into whatever space the consumer gives DocumentView.
private const val TARGET_PAGE_WIDTH_DP: Int = 360
private const val TARGET_PAGE_HEIGHT_DP: Int = 480

// Zoom bounds for ZoomableDocumentPage (wpass-1wq).
//
// MIN_SCALE = 1f: zooming out below fit is meaningless on a single-page surface — the
// page is already letterboxed and ContentScale.Fit owns "see the whole page in one
// glance." Below 1 would just paint a smaller page inside empty slot real estate.
//
// MAX_SCALE = 3f (interim): held at 3x rather than 5x until wpass-f4b lands the
// viewport-aware renderer. Against a fit-resolution bitmap, every source pixel
// becomes a 3x3 block under bilinear filtering at this scale, which is usually
// still inside the decoder's edge-detection budget for PDF417 / QR / EAN — at 5x
// each source pixel is a 5x5 block, and the smearing crosses into "looks bigger
// but actually less scannable" territory for many phone-camera scanners. Once
// wpass-f4b re-renders the visible viewport at viewport-pixel resolution within
// the same 4 MP cap, the upsampling cost disappears and this constant can move
// to 5f without producing a degraded UX at the top end.
//
// DOUBLE_TAP_SCALE = 2f: the toggle-target for a double-tap. Held below MAX_SCALE
// so a single tap is a clear "zoom in" affordance that picks the
// most-likely-actually-readable point, with the user free to pinch further if a
// specific scanner needs more.
private const val MIN_SCALE: Float = 1f
private const val MAX_SCALE: Float = 3f
private const val DOUBLE_TAP_SCALE: Float = 2f
