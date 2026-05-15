package `is`.walt.passes.pdf.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
 * Pages are rasterised on demand by the isolated-process renderer reached through
 * [renderer] (the `PdfRendererBinder` interface, never the concrete `PdfRendererClient`,
 * so test fakes substitute cleanly) and held in a small per-document LRU cache to
 * amortise re-render on quick swipes.
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
 *  - Inline surface is fixed 1x: no pinch-zoom, no pan, no double-tap. Zoom lives only
 *    on the full-screen detail surface (wpass-ny4 / wpass-jil).
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
@Suppress("LongParameterList")
public fun DocumentView(
    doc: PdfDocument,
    pdfFile: ParcelFileDescriptor,
    renderer: PdfRendererBinder,
    modifier: Modifier = Modifier,
    telemetry: DocumentTelemetryGuard = DocumentTelemetryGuard.NoOp,
    onOpenFullScreen: (() -> Unit)? = null,
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
        DocumentTrustCaption()

        // `laneBackground` is painted behind the pager only — it is the document-surface
        // tone the rasterised page sits on (showing through ContentScale.Fit letterbox
        // bars). The trust caption above sits on the consumer's background, reading as
        // host screen chrome.
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

        // wpass-jil: full-screen banner. Only shown when the host supplies a navigation
        // callback. Docked at the bottom of the page region, below the pager and above
        // any other host chrome. The trust caption remains structurally above the pager
        // in this Column, so the banner cannot push it off-screen.
        if (onOpenFullScreen != null) {
            FullScreenBanner(onClick = onOpenFullScreen)
        }
    }
}

// wpass-jil: docked banner inside DocumentView whose tap launches the full-screen
// detail surface. Label and colours come from DocumentSemantics so the host owns the
// wording and styling.
@Composable
private fun FullScreenBanner(onClick: () -> Unit) {
    val semantics = LocalDocumentSemantics.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantics.fullScreenBannerBackground.toComposeColor())
            .clickable(onClick = onClick)
            .padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = semantics.fullScreenBannerLabel,
            style = MaterialTheme.typography.labelMedium,
            color = semantics.fullScreenBannerForeground.toComposeColor(),
        )
    }
}

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
            // return a downsized buffer. Use the renderer's reported dimensions, NOT
            // the request, when reconstructing the Bitmap.
            val bitmap = bitmapFromSharedMemory(result, telemetry)
            if (bitmap != null) {
                cache.put(document.id, pageIndex, bitmap)
                rendered = bitmap.asImageBitmap()
            }
        }
    }

    // The page fills the slot the pager hands it (the pager's `weight(1f)` share of
    // DocumentView's Column) and lets ContentScale.Fit letterbox the bitmap inside it.
    // Fixed 1x — zoom lives in the full-screen surface (wpass-ny4 / wpass-jil).
    rendered?.let { bitmap ->
        Image(
            bitmap = bitmap,
            // ADR 0005 D4 forbids extracting text from the PDF; positional caption
            // is the only safe TalkBack fallback.
            contentDescription = "Page ${pageIndex + 1} of ${document.pageCount}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
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
