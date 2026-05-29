package `is`.walt.passes.pdf.ui

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import `is`.walt.passes.pdf.DocumentTelemetryGuard
import `is`.walt.passes.pdf.PdfDocument
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.pdf.ui.theme.LocalDocumentSemantics
import `is`.walt.passes.ui.core.toComposeColor

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
 * @param fullScreenAffordance Host-supplied open-full-screen affordance, composed only
 *   when [onOpenFullScreen] is non-null and floated over the bottom-centre of the page
 *   region. Mirrors the `closeButton` slot on [FullScreenDocumentView]: the surface owns
 *   placement and wiring (it is handed the open callback), the host owns chrome — shape,
 *   corner radius, leading icon, label, padding (wpass-emn). The default renders the
 *   kernel's neutral banner so existing label-and-colour-only consumers are unchanged.
 *   This slot cannot suppress the trust caption or the page tap target; both are
 *   structural and independent of it.
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
    fullScreenAffordance: @Composable (onOpen: () -> Unit) -> Unit = { onOpen ->
        FullScreenBanner(onClick = onOpen)
    },
) {
    val semantics = LocalDocumentSemantics.current
    val cache = remember(doc.id) { PdfThumbnailCache() }
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
        // host screen chrome. The page region is a Box so the open-full-screen affordance
        // can float over its bottom edge (wpass-emn) rather than dock below it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(semantics.laneBackground.toComposeColor()),
        ) {
            // When the host wires `onOpenFullScreen`, a tap anywhere on the page region
            // launches the full-screen surface — the affordance is a discoverability
            // hint, the page itself is the primary tap target. `.clickable` and the
            // pager's drag handling coexist: Compose routes quick press-and-release to
            // the click handler and horizontal drag to the pager.
            val pagerModifier = Modifier
                .fillMaxSize()
                .let {
                    if (onOpenFullScreen != null) it.clickable(onClick = onOpenFullScreen) else it
                }
                .padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp))
            HorizontalPager(state = pagerState, modifier = pagerModifier) { page ->
                DocumentPage(
                    document = doc,
                    pageIndex = page,
                    pdfFile = pdfFile,
                    renderer = renderer,
                    cache = cache,
                    telemetry = telemetry,
                )
            }

            // wpass-jil / wpass-emn: open-full-screen affordance. Opt-in via
            // `onOpenFullScreen` — hosts without a full-screen route render nothing here
            // (the page tap above remains the affordance). When wired, the host-supplied
            // `fullScreenAffordance` floats over the bottom-centre of the page; the
            // default is the kernel's neutral banner. The trust caption sits above this
            // Box in the Column, so the affordance can never overlap or suppress it.
            if (onOpenFullScreen != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                ) {
                    fullScreenAffordance(onOpenFullScreen)
                }
            }
        }
    }
}

// wpass-jil: the kernel's neutral default open-full-screen affordance, floated over the
// page bottom. Label and colours come from DocumentSemantics so the host owns wording
// and styling; a host wanting a different register (pill, leading icon, placement)
// supplies its own via DocumentView's `fullScreenAffordance` slot (wpass-emn).
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
    cache: PdfThumbnailCache,
    telemetry: DocumentTelemetryGuard,
) {
    val density = LocalDensity.current
    val requestWidthPx = with(density) {
        TARGET_PAGE_WIDTH_DP.dp.toPx().toInt().coerceAtLeast(1)
    }
    val requestHeightPx = with(density) {
        TARGET_PAGE_HEIGHT_DP.dp.toPx().toInt().coerceAtLeast(1)
    }

    val state = rememberPdfThumbnail(
        document = document,
        pdfFile = pdfFile,
        renderer = renderer,
        targetSizePx = IntSize(requestWidthPx, requestHeightPx),
        page = pageIndex,
        telemetry = telemetry,
        cache = cache,
    )

    // The page fills the slot the pager hands it (the pager's `weight(1f)` share of
    // DocumentView's Column) and lets ContentScale.Fit letterbox the bitmap inside it.
    // Fixed 1x — zoom lives in the full-screen surface (wpass-ny4 / wpass-jil). The
    // Loading and Failed arms render nothing in the inline surface; the pager itself
    // is the placeholder. A future affordance for either belongs on `DocumentTile`,
    // not here.
    when (state) {
        is PdfThumbnailState.Loading, is PdfThumbnailState.Failed -> Unit
        is PdfThumbnailState.Rendered -> Image(
            bitmap = state.image,
            // ADR 0005 D4 forbids extracting text from the PDF; positional caption
            // is the only safe TalkBack fallback.
            contentDescription = "Page ${pageIndex + 1} of ${document.pageCount}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// Render budget defaults. The renderer service caps the bitmap area independently
// (ADR 0005 D7); these are the UI-side request size for the inline surface.
// Unlike `FullScreenDocumentView` (which derives the request from the actual slot
// via BoxWithConstraints), the inline surface is fixed 1x and ContentScale.Fit masks
// any over/under-render — the BoxWithConstraints cost isn't worth it for a thumbnail-
// sized slot.
private const val TARGET_PAGE_WIDTH_DP: Int = 360
private const val TARGET_PAGE_HEIGHT_DP: Int = 480
