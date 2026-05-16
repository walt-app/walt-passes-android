package `is`.walt.passes.pdf.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import `is`.walt.passes.pdf.DocumentTelemetryGuard
import `is`.walt.passes.pdf.PdfDocument
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.pdf.android.RenderResult
import `is`.walt.passes.pdf.android.RenderSourceRect
import `is`.walt.passes.pdf.ui.internal.LRU_PAGE_WINDOW
import `is`.walt.passes.pdf.ui.internal.ZoomableImage
import `is`.walt.passes.pdf.ui.internal.decodePage
import `is`.walt.passes.pdf.ui.internal.renderOrDiscard
import `is`.walt.passes.pdf.ui.theme.LocalDocumentSemantics
import `is`.walt.passes.ui.core.toComposeColor

/**
 * Full-screen detail surface for a [PdfDocument] (`wpass-jil`). The ONLY place inside
 * `passes-pdf-ui` where pinch-zoom and pan are available; inline `DocumentView` is
 * fixed 1x after the `wpass-ny4` pivot.
 *
 * Trust contract:
 *
 *  - The non-suppressible [DocumentTrustCaption] is composed inside this surface and
 *    docked to the bottom edge of the screen, structurally outside the zoom transform
 *    (ADR 0005 D5 / Z.8).
 *  - Zoom is purely view-side. No share / export / print / open-with affordance
 *    (ADR 0005 D8); `DocumentPublicApiSurfaceTest` continues to enforce the
 *    classpath-scan rule on `Intent.ACTION_SEND`.
 *  - On pinch settle the surface fires a `renderer.render(SubRect)` call against the
 *    currently-visible normalised page rect and SWAPS the displayed bitmap when the
 *    result returns, so the visible region stays sharp within the 4 MP per-bitmap cap
 *    (`wpass-f4b`).
 *
 * Page cache: fit-resolution renders are cached per page; sub-rect renders are not
 * (cache-keying them would explode the key space and stale entries surface as a
 * sharper-but-wrong region after a pan).
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
    val cache = remember(doc.id) { PdfThumbnailCache(maxSize = LRU_PAGE_WINDOW) }
    DisposableEffect(doc.id) {
        onDispose { cache.clear() }
    }

    val pagerState = rememberPagerState(pageCount = { doc.pageCount })

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(semantics.laneBackground.toComposeColor()),
    ) {
        // Trust caption docked to bottom edge; outside the zoom transform (Z.8). The
        // pager is offset above it via a BottomDockedLayout so the page surface uses
        // the actual measured caption height instead of a hardcoded constant
        // (`wpass-6ag` review M2 partial — drives the pager padding from the caption's
        // intrinsic height, no SubcomposeLayout needed because the caption is a sibling
        // in a Box layered above).
        BottomDockedLayout(
            dock = { DocumentTrustCaption() },
            modifier = Modifier.fillMaxSize(),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
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
        }

        Box(modifier = Modifier.align(Alignment.TopStart)) {
            CloseFullScreenButton(onClick = onClose)
        }
    }
}

/**
 * Hosts the pager content in the region above [dock]. The dock composes at its natural
 * height at the bottom; the content fills the rest. Avoids the hardcoded
 * TRUST_CAPTION_DOCK_HEIGHT constant the original full-screen surface used.
 */
@Composable
private fun BottomDockedLayout(
    dock: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) { content() }
        Box(modifier = Modifier.fillMaxWidth()) { dock() }
    }
}

@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
private fun FullScreenPage(
    document: PdfDocument,
    pageIndex: Int,
    pdfFile: ParcelFileDescriptor,
    renderer: PdfRendererBinder,
    cache: PdfThumbnailCache,
    telemetry: DocumentTelemetryGuard,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // wpass-6ag review A3: request size derived from the actual pager slot, not a
        // hardcoded dp pair. Clamped at MAX_REQUEST_PIXELS so the renderer is never
        // asked to allocate beyond the 4 MP cap (ADR 0005 D7).
        val (requestW, requestH) = with(density) {
            val rawW = maxWidth.toPx().toInt().coerceAtLeast(1)
            val rawH = maxHeight.toPx().toInt().coerceAtLeast(1)
            clampToMaxPixels(rawW, rawH, MAX_REQUEST_PIXELS)
        }

        // Base page rendering goes through the shared public facade so the
        // NonCancellable transact, SharedMemory cleanup, and LRU eviction live in
        // exactly one place. The sub-rect zoom path below stays manual: sub-rects
        // are not cache-keyed (would explode the key space; stale entries surface as
        // sharper-but-wrong regions after a pan) and need bespoke overlay lifetime.
        val baseState = rememberPdfThumbnail(
            document = document,
            pdfFile = pdfFile,
            renderer = renderer,
            targetSizePx = IntSize(requestW, requestH),
            page = pageIndex,
            telemetry = telemetry,
            cache = cache,
        )
        val baseRendered = baseState as? PdfThumbnailState.Rendered

        var zoomedReplacement by remember(document.id, pageIndex) {
            mutableStateOf<ImageBitmap?>(null)
        }
        var zoomedReplacementHandle by remember(document.id, pageIndex) {
            mutableStateOf<Bitmap?>(null)
        }
        var pendingRect by remember(document.id, pageIndex) {
            mutableStateOf<RenderSourceRect?>(null)
        }

        DisposableEffect(document.id, pageIndex) {
            onDispose {
                zoomedReplacementHandle?.let { if (!it.isRecycled) it.recycle() }
                zoomedReplacementHandle = null
                zoomedReplacement = null
            }
        }

        baseRendered?.let { rendered ->
            ZoomableImage(
                bitmap = rendered.image,
                // ADR 0005 D4 forbids extracting text; the positional caption is the
                // only safe TalkBack fallback.
                contentDescription = "Page ${pageIndex + 1} of ${document.pageCount}",
                modifier = Modifier.fillMaxSize(),
                pageAspect = rendered.pageAspect,
                zoomedReplacement = zoomedReplacement,
                // wpass-6ag review M3: edge-triggered. New gesture clears the prior
                // sub-rect bitmap and frees its native memory so the next pinch starts
                // from the base bitmap with the live transform.
                onTransformStarted = {
                    zoomedReplacementHandle?.let { if (!it.isRecycled) it.recycle() }
                    zoomedReplacementHandle = null
                    zoomedReplacement = null
                },
                onZoomedRegionChanged = { rect -> pendingRect = rect },
            )
        }

        LaunchedEffect(pendingRect, document.id, pageIndex, requestW, requestH) {
            val pending = pendingRect ?: return@LaunchedEffect
            // wpass-6ag review N1: stale-or-cancelled results free their SharedMemory
            // before we drop them. The NonCancellable inside renderOrDiscard guarantees
            // we own the result even if a newer pinch (re-keying this LaunchedEffect)
            // strikes while binder.transact is in flight.
            val result = renderOrDiscard(
                renderer = renderer,
                pdf = pdfFile,
                page = pageIndex,
                widthPx = requestW,
                heightPx = requestH,
                sourceRect = pending,
                isStillWanted = { pendingRect === pending },
            ) ?: return@LaunchedEffect
            if (result is RenderResult.Ok) {
                val decoded = decodePage(result, telemetry)
                if (decoded != null) {
                    zoomedReplacementHandle?.let { if (!it.isRecycled) it.recycle() }
                    zoomedReplacementHandle = decoded.bitmap
                    zoomedReplacement = decoded.image
                }
            }
        }
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
            text = semantics.closeFullScreenLabel,
            color = semantics.fullScreenBannerForeground.toComposeColor(),
        )
    }
}

private fun clampToMaxPixels(widthPx: Int, heightPx: Int, maxPixels: Long): Pair<Int, Int> {
    val product = widthPx.toLong() * heightPx.toLong()
    if (product <= maxPixels) return widthPx to heightPx
    val scale = kotlin.math.sqrt(maxPixels.toDouble() / product.toDouble())
    val w = (widthPx * scale).toInt().coerceAtLeast(1)
    val h = (heightPx * scale).toInt().coerceAtLeast(1)
    return w to h
}

// Mirrors PdfRendererService.MAX_PIXELS so the request never asks for a bitmap the
// renderer would have to downsize on its end. Concrete value lives in passes-pdf; this
// re-declaration is a defensive ceiling, not the load-bearing cap.
private const val MAX_REQUEST_PIXELS: Long = 4L * 1024 * 1024
