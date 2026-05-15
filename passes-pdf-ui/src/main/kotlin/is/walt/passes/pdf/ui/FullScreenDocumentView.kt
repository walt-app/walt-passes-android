package `is`.walt.passes.pdf.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import `is`.walt.passes.pdf.DocumentTelemetryGuard
import `is`.walt.passes.pdf.PdfDocument
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.pdf.android.RenderResult
import `is`.walt.passes.pdf.android.RenderSourceRect
import `is`.walt.passes.pdf.ui.internal.RenderedPageCache
import `is`.walt.passes.pdf.ui.internal.ZoomableImage
import `is`.walt.passes.pdf.ui.theme.LocalDocumentSemantics
import `is`.walt.passes.ui.core.toComposeColor
import kotlinx.coroutines.launch

/**
 * Full-screen detail surface for a [PdfDocument] (`wpass-jil`). The ONLY place inside
 * `passes-pdf-ui` where pinch-zoom and pan are available; inline `DocumentView` is fixed
 * 1x after the `wpass-ny4` pivot.
 *
 * Trust contract:
 *
 *  - The non-suppressible [DocumentTrustCaption] is composed inside this surface and
 *    docked to the bottom edge of the screen. It is NOT subject to the zoom transform
 *    and cannot be panned off-screen (ADR 0005 D5 / addendum Z.8). The caption sits
 *    above the page region in the [Box] and is structurally outside the zoom surface.
 *  - Zoom is purely view-side. No share, export, print, or open-with affordance
 *    (ADR 0005 D8); `DocumentPublicApiSurfaceTest` continues to enforce the
 *    classpath-scan rule on `Intent.ACTION_SEND`.
 *  - On pinch settle the surface fires a `renderer.render(... SubRect ...)` call
 *    against the currently-visible normalised page rect so the displayed bitmap stays
 *    sharp within the unchanged 4 MP per-bitmap cap (`wpass-f4b`).
 *
 * Page cache: a per-document LRU mirrors [DocumentView]'s cache so swiping back and
 * forth between pages re-uses fit-resolution renders. Sub-rect renders are NOT cached
 * (they would explode the cache key space and stale entries would surface as a
 * sharper-but-wrong region after a pan).
 *
 * [pdfFile] ownership matches [DocumentView]: the caller keeps the descriptor open for
 * as long as `FullScreenDocumentView` is composed.
 */
@Composable
@Suppress("LongParameterList")
public fun FullScreenDocumentView(
    doc: PdfDocument,
    pdfFile: ParcelFileDescriptor,
    renderer: PdfRendererBinder,
    onClose: () -> Unit,
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(semantics.laneBackground.toComposeColor()),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(PaddingValues(bottom = TRUST_CAPTION_DOCK_HEIGHT_DP.dp)),
        ) { page ->
            FullScreenPage(
                document = doc,
                pageIndex = page,
                pdfFile = pdfFile,
                renderer = renderer,
                cache = cache,
                telemetry = telemetry,
            )
        }

        // ADR 0005 Z.8: trust caption docks to a screen edge in the full-screen surface
        // and is NOT subject to the zoom transform. Aligned to the bottom of the root
        // Box so pan within the pager cannot push it off-screen.
        Box(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
            DocumentTrustCaption()
        }

        // Discreet close affordance docked to the top-start corner. Hosts that prefer a
        // gesture-only close hide it via their own theming chrome.
        Box(modifier = Modifier.align(Alignment.TopStart)) {
            CloseFullScreenButton(onClick = onClose)
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun FullScreenPage(
    document: PdfDocument,
    pageIndex: Int,
    pdfFile: ParcelFileDescriptor,
    renderer: PdfRendererBinder,
    cache: RenderedPageCache<Bitmap>,
    telemetry: DocumentTelemetryGuard,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var rendered by remember(document.id, pageIndex) {
        mutableStateOf<ImageBitmap?>(cache.get(document.id, pageIndex)?.asImageBitmap())
    }
    val requestWidthPx = with(density) { FULL_SCREEN_PAGE_WIDTH_DP.dp.toPx().toInt().coerceAtLeast(1) }
    val requestHeightPx = with(density) { FULL_SCREEN_PAGE_HEIGHT_DP.dp.toPx().toInt().coerceAtLeast(1) }

    LaunchedEffect(document.id, pageIndex, requestWidthPx, requestHeightPx) {
        val cached = cache.get(document.id, pageIndex)
        if (cached != null && !cached.isRecycled) {
            rendered = cached.asImageBitmap()
            return@LaunchedEffect
        }
        val result = renderer.render(
            pdf = pdfFile,
            page = pageIndex,
            widthPx = requestWidthPx,
            heightPx = requestHeightPx,
            sourceRect = RenderSourceRect.FullPage,
        )
        if (result is RenderResult.Ok) {
            val bitmap = fullScreenBitmapFromSharedMemory(result, telemetry)
            if (bitmap != null) {
                cache.put(document.id, pageIndex, bitmap)
                rendered = bitmap.asImageBitmap()
            }
        }
    }

    rendered?.let { bitmap ->
        ZoomableImage(
            bitmap = bitmap,
            // ADR 0005 D4 forbids extracting text from the PDF; positional caption
            // is the only safe TalkBack fallback.
            contentDescription = "Page ${pageIndex + 1} of ${document.pageCount}",
            modifier = Modifier.fillMaxSize(),
            // wpass-jil: invoked after the gesture settles. The sub-rect re-render
            // keeps the visible region sharp within the 4 MP per-bitmap cap.
            onZoomedRegionChanged = { rect ->
                scope.launch {
                    renderer.render(
                        pdf = pdfFile,
                        page = pageIndex,
                        widthPx = requestWidthPx,
                        heightPx = requestHeightPx,
                        sourceRect = rect,
                    )
                }
            },
        )
    }
}

@Composable
private fun CloseFullScreenButton(onClick: () -> Unit) {
    val semantics = LocalDocumentSemantics.current
    Box(
        modifier = Modifier
            .padding(12.dp)
            .background(semantics.fullScreenBannerBackground.toComposeColor())
            .clickable(onClick = onClick)
            .padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp)),
    ) {
        Text(
            text = "Close",
            color = semantics.fullScreenBannerForeground.toComposeColor(),
        )
    }
}

// Same reconstruction shape as the inline DocumentView path; lifted into its own
// helper so the full-screen surface does not depend on DocumentView's private helpers.
private fun fullScreenBitmapFromSharedMemory(
    ok: RenderResult.Ok,
    telemetry: DocumentTelemetryGuard,
): Bitmap? =
    runCatching {
        val mapped = ok.sharedMemory.mapReadOnly()
        try {
            val bitmap = Bitmap.createBitmap(ok.widthPx, ok.heightPx, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(mapped)
            bitmap
        } finally {
            android.os.SharedMemory.unmap(mapped)
            ok.sharedMemory.close()
        }
    }.onFailure { t -> telemetry.onConsumerRenderFailed(consumerRenderFailureFor(t)) }
        .getOrNull()

// Caps the pixel-size of the initial fit-resolution render on the full-screen surface.
// Chosen to give a reasonable ARGB_8888 budget on phone-class displays without crossing
// the 4 MP cap; sub-rect re-renders use the same widthPx / heightPx (= viewport
// resolution) so the renderer never exceeds the cap.
private const val FULL_SCREEN_PAGE_WIDTH_DP: Int = 720
private const val FULL_SCREEN_PAGE_HEIGHT_DP: Int = 960

// Reserved at the bottom of the full-screen surface for the docked trust caption so the
// pager does not paint behind it.
private const val TRUST_CAPTION_DOCK_HEIGHT_DP: Int = 56
