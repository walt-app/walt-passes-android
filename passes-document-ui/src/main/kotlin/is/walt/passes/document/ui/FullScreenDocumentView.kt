package `is`.walt.passes.document.ui

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
import `is`.walt.passes.document.BarcodedImageDocument
import `is`.walt.passes.document.Document
import `is`.walt.passes.document.DocumentId
import `is`.walt.passes.document.DocumentTelemetryGuard
import `is`.walt.passes.document.ImageDocument
import `is`.walt.passes.document.PdfDocument
import `is`.walt.passes.image.android.ImageDecodeBinder
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.pdf.android.RenderResult
import `is`.walt.passes.pdf.android.RenderSourceRect
import `is`.walt.passes.document.ui.internal.DEFAULT_MAX_SCALE
import `is`.walt.passes.document.ui.internal.ZoomableImage
import `is`.walt.passes.document.ui.internal.decodePage
import `is`.walt.passes.document.ui.internal.renderOrDiscard
import `is`.walt.passes.document.ui.theme.LocalDocumentSemantics
import `is`.walt.passes.ui.core.toComposeColor

/**
 * Full-screen detail surface for a [Document] (`wpass-jil`; generalized past PDF-only by
 * `wpass-pl7.4`). The ONLY place inside `passes-document-ui` where pinch-zoom and pan are
 * available; inline `DocumentView` is fixed 1x after the `wpass-ny4` pivot.
 *
 * Like [DocumentView], this is a dispatcher on the sealed [Document] type: [PdfDocument]
 * gets a swipeable pager of zoomable pages over the isolated PDF renderer;
 * [ImageDocument] gets a single zoomable image over the isolated image-decode sandbox;
 * [BarcodedImageDocument] (wpass-8lu) routes to the SAME image surface for its image half —
 * the generated barcode and format switcher are composed by the consumer with `passes-ui`,
 * so this surface stays image-only and the two UI towers remain independent.
 *
 * The backend handles are kind-specific and nullable, mirroring [DocumentView]: supply the
 * [pdfFile] / [renderer] pair for a [PdfDocument] and the [imageFile] / [imageDecoder] pair
 * for an [ImageDocument] or [BarcodedImageDocument]. The dispatcher requires the pair that
 * matches the arm; passing a document without its backend pair is a programming error and
 * fails fast.
 *
 * Trust contract:
 *
 *  - The non-suppressible [DocumentTrustCaption] is composed inside this surface and
 *    docked to the bottom edge of the screen, structurally outside the zoom transform
 *    (ADR 0005 D5 / Z.8) — the dock is a sibling of the arm dispatch, so no arm can scale
 *    or translate it. Zooming can never push the caption off-surface.
 *  - Zoom is purely view-side. No share / export / print / open-with affordance
 *    (ADR 0005 D8); `DocumentPublicApiSurfaceTest` continues to enforce the
 *    classpath-scan rule on `Intent.ACTION_SEND`.
 *  - PDF arm: on pinch settle the surface fires a `renderer.render(SubRect)` call against
 *    the currently-visible normalised page rect and SWAPS the displayed bitmap when the
 *    result returns, so the visible region stays sharp within the 4 MP per-bitmap cap
 *    (`wpass-f4b`).
 *  - Image arm: `ImageDecodeBinder` has no sub-rect API, so there is no re-render on
 *    settle. Instead the single decode is requested at the slot size scaled by the max
 *    zoom factor (clamped to the same 4 MP cap the decode service enforces), and the
 *    sandbox never upscales beyond the source — so the raster already carries the
 *    sharpest legal pixels for every zoom level this surface allows.
 *
 * Page cache (PDF arm): fit-resolution renders are cached per page; sub-rect renders are
 * not (cache-keying them would explode the key space and stale entries surface as a
 * sharper-but-wrong region after a pan).
 *
 * @param closeButton Host-supplied close affordance, rendered at [Alignment.TopStart].
 *   The surface owns placement and [onClose] wiring; the host owns chrome, sizing, and
 *   any inset / padding handling (the default applies its own padding, host overrides
 *   should apply their own — e.g. `windowInsetsPadding(WindowInsets.statusBars)` on
 *   edge-to-edge displays so the chip never slides under the system clock).
 */
@Composable
@Suppress("LongParameterList")
public fun FullScreenDocumentView(
    doc: Document,
    pdfFile: ParcelFileDescriptor? = null,
    renderer: PdfRendererBinder? = null,
    onClose: () -> Unit,
    imageFile: ParcelFileDescriptor? = null,
    imageDecoder: ImageDecodeBinder? = null,
    modifier: Modifier = Modifier,
    telemetry: DocumentTelemetryGuard = DocumentTelemetryGuard.NoOp,
    closeButton: @Composable (onClose: () -> Unit) -> Unit = { handler ->
        CloseFullScreenButton(onClick = handler)
    },
) {
    val semantics = LocalDocumentSemantics.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(semantics.laneBackground.toComposeColor()),
    ) {
        // Trust caption docked to bottom edge; outside the zoom transform (Z.8) for EVERY
        // arm — the dock is a sibling of the arm dispatch, so a new Document arm cannot
        // put content around the caption. The content region is offset above it via a
        // BottomDockedLayout so the surface uses the actual measured caption height
        // instead of a hardcoded constant (`wpass-6ag` review M2 partial).
        BottomDockedLayout(
            dock = { DocumentTrustCaption() },
            modifier = Modifier.fillMaxSize(),
        ) {
            when (doc) {
                is PdfDocument -> FullScreenPdfPager(
                    doc = doc,
                    pdfFile = requireNotNull(pdfFile) {
                        "FullScreenDocumentView(PdfDocument) requires a non-null pdfFile"
                    },
                    renderer = requireNotNull(renderer) {
                        "FullScreenDocumentView(PdfDocument) requires a non-null renderer"
                    },
                    telemetry = telemetry,
                )
                is ImageDocument -> FullScreenImagePage(
                    documentId = doc.id,
                    imageFile = requireNotNull(imageFile) {
                        "FullScreenDocumentView(ImageDocument) requires a non-null imageFile"
                    },
                    decoder = requireNotNull(imageDecoder) {
                        "FullScreenDocumentView(ImageDocument) requires a non-null imageDecoder"
                    },
                    telemetry = telemetry,
                )
                // wpass-8lu / wpass-pl7.4: a composite's retained photo zooms through the same
                // isolated image-decode surface as a plain image; the barcode half stays with
                // the consumer's passes-ui composition.
                is BarcodedImageDocument -> FullScreenImagePage(
                    documentId = doc.id,
                    imageFile = requireNotNull(imageFile) {
                        "FullScreenDocumentView(BarcodedImageDocument) requires a non-null imageFile"
                    },
                    decoder = requireNotNull(imageDecoder) {
                        "FullScreenDocumentView(BarcodedImageDocument) requires a non-null imageDecoder"
                    },
                    telemetry = telemetry,
                )
            }
        }

        Box(modifier = Modifier.align(Alignment.TopStart)) {
            closeButton(onClose)
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
private fun FullScreenPdfPager(
    doc: PdfDocument,
    pdfFile: ParcelFileDescriptor,
    renderer: PdfRendererBinder,
    telemetry: DocumentTelemetryGuard,
) {
    val cache = remember(doc.id) { PdfThumbnailCache() }
    DisposableEffect(doc.id) {
        onDispose { cache.clear() }
    }

    val pagerState = rememberPagerState(pageCount = { doc.pageCount })

    // Deliberately no beyondViewportPageCount here (unlike DocumentView's inline
    // pager): full-screen rasters are far larger and the peek redesign does not
    // apply to this surface (wpass-tjc.3).
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

/**
 * The image-arm zoom surface: a single decoded raster inside [ZoomableImage], no pager. The
 * decode is requested at the slot size scaled by [DEFAULT_MAX_SCALE] (clamped to the 4 MP
 * cap) so pinching to max zoom shows real source pixels where the source has them — the
 * sandbox bounds the output to fit the request and never upscales beyond the source, so
 * over-asking costs only what the image actually carries.
 */
@Composable
private fun FullScreenImagePage(
    documentId: DocumentId,
    imageFile: ParcelFileDescriptor,
    decoder: ImageDecodeBinder,
    telemetry: DocumentTelemetryGuard,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val (requestW, requestH) = with(density) {
            val rawW = (maxWidth.toPx() * DEFAULT_MAX_SCALE).toInt().coerceAtLeast(1)
            val rawH = (maxHeight.toPx() * DEFAULT_MAX_SCALE).toInt().coerceAtLeast(1)
            clampToMaxPixels(rawW, rawH, MAX_REQUEST_PIXELS)
        }

        val state = rememberDocumentImage(
            documentId = documentId,
            imageFile = imageFile,
            decoder = decoder,
            targetSizePx = IntSize(requestW, requestH),
            telemetry = telemetry,
        )
        // Loading / Failed render nothing; the lane tone is the placeholder, matching the
        // PDF arm's base-bitmap gate.
        when (state) {
            is DocumentImageState.Loading, is DocumentImageState.Failed -> Unit
            is DocumentImageState.Rendered -> ZoomableImage(
                bitmap = state.image,
                // ADR 0005 D4 forbids extracting text/metadata from the image; a fixed
                // neutral description is the only safe TalkBack fallback.
                contentDescription = "Image document",
                modifier = Modifier.fillMaxSize(),
                pageAspect = state.sourceAspect,
            )
        }
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
        // Loading and Failed render nothing; the zoom path requires the base bitmap
        // and aspect to exist before it can compose the overlay. Both arms map to
        // "no base image yet."
        val baseRendered = when (baseState) {
            is PdfThumbnailState.Loading, is PdfThumbnailState.Failed -> null
            is PdfThumbnailState.Rendered -> baseState
        }

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

// Mirrors PdfRendererService.MAX_PIXELS (and ImageDecodeConfig.maxOutputPixels) so the
// request never asks for a bitmap the sandbox would have to downsize on its end. Concrete
// values live in passes-pdf / passes-image; this re-declaration is a defensive ceiling,
// not the load-bearing cap.
private const val MAX_REQUEST_PIXELS: Long = 4L * 1024 * 1024
