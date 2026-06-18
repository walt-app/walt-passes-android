package `is`.walt.passes.document.ui

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
import `is`.walt.passes.image.android.ImageDecodeBinder
import `is`.walt.passes.document.BarcodedImageDocument
import `is`.walt.passes.document.Document
import `is`.walt.passes.document.DocumentId
import `is`.walt.passes.document.DocumentTelemetryGuard
import `is`.walt.passes.document.ImageDocument
import `is`.walt.passes.document.PdfDocument
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.document.ui.theme.LocalDocumentSemantics
import `is`.walt.passes.ui.core.toComposeColor

/**
 * Presentation of a [Document] — the public entry point the consumer composes. This is a
 * dispatcher on the sealed [Document] type: it selects the surface for the concrete arm and
 * forwards the consumer's parameters. [PdfDocument] routes to [PdfDocumentView] (a swipeable
 * pager over the isolated PDF renderer); [ImageDocument] routes to [ImageDocumentView] (a
 * single, no-pager image over the isolated image-decode sandbox); [BarcodedImageDocument]
 * (wpass-8lu) routes to the SAME [ImageDocumentView] for its image half — the generated barcode
 * and format switcher are composed by the consumer with `passes-ui`, so this surface stays
 * image-only and the two UI towers remain independent. The trust caption is non-suppressible
 * inside every arm — this dispatcher adds no parameter that could omit it, so the
 * [DocumentSurfaceLockTest] shape lock and [DocumentTrustSurfaceTest] visible-text lock hold
 * across the seam.
 *
 * The backend handles are kind-specific and nullable: a consumer supplies the [pdfFile] /
 * [renderer] pair for a [PdfDocument] and the [imageFile] / [imageDecoder] pair for an
 * [ImageDocument]. The dispatcher requires the pair that matches the arm; passing a document
 * without its backend pair is a programming error and fails fast. This keeps a consumer that
 * only ever shows one kind from having to fabricate the other backend.
 *
 * [trustCaption] selects how the provenance signal is carried: with
 * [TrustCaptionPlacement.HostedTypeRow] each arm renders no caption because the host carries
 * the claim itself, as a "Pass type" row inside its own details section (values "PDF" /
 * "Image" / "Image, Scanned"). Under that mode a neutral type label is an accepted carrier
 * and the row may sit in a collapsed-by-default foldout — see `TrustCaptionPlacement` and
 * the ADR 0005 D5 "Pass type" row addendum. Defaults to [TrustCaptionPlacement.Docked] (the
 * verbatim docked caption) so every existing caller is unchanged.
 */
@Composable
@Suppress("LongParameterList")
public fun DocumentView(
    doc: Document,
    pdfFile: ParcelFileDescriptor? = null,
    renderer: PdfRendererBinder? = null,
    imageFile: ParcelFileDescriptor? = null,
    imageDecoder: ImageDecodeBinder? = null,
    modifier: Modifier = Modifier,
    telemetry: DocumentTelemetryGuard = DocumentTelemetryGuard.NoOp,
    trustCaption: TrustCaptionPlacement = TrustCaptionPlacement.Docked,
    onOpenFullScreen: (() -> Unit)? = null,
    fullScreenAffordance: (@Composable (onOpen: () -> Unit) -> Unit)? = null,
) {
    when (doc) {
        is PdfDocument -> PdfDocumentView(
            doc = doc,
            pdfFile = requireNotNull(pdfFile) { "DocumentView(PdfDocument) requires a non-null pdfFile" },
            renderer = requireNotNull(renderer) { "DocumentView(PdfDocument) requires a non-null renderer" },
            modifier = modifier,
            telemetry = telemetry,
            trustCaption = trustCaption,
            onOpenFullScreen = onOpenFullScreen,
            fullScreenAffordance = fullScreenAffordance,
        )
        is ImageDocument -> ImageDocumentView(
            documentId = doc.id,
            imageFile = requireNotNull(imageFile) { "DocumentView(ImageDocument) requires a non-null imageFile" },
            decoder = requireNotNull(imageDecoder) { "DocumentView(ImageDocument) requires a non-null imageDecoder" },
            modifier = modifier,
            telemetry = telemetry,
            trustCaption = trustCaption,
        )
        // wpass-8lu: a composite renders its IMAGE half through the same isolated image-decode
        // surface as a plain image (same imageFile / imageDecoder pair, no new DocumentView
        // parameter). The generated barcode + format switcher are composed by the consumer with
        // passes-ui, keeping the two UI towers independent — this surface stays image-only.
        is BarcodedImageDocument -> ImageDocumentView(
            documentId = doc.id,
            imageFile = requireNotNull(imageFile) {
                "DocumentView(BarcodedImageDocument) requires a non-null imageFile"
            },
            decoder = requireNotNull(imageDecoder) {
                "DocumentView(BarcodedImageDocument) requires a non-null imageDecoder"
            },
            modifier = modifier,
            telemetry = telemetry,
            trustCaption = trustCaption,
        )
    }
}

/**
 * Presentation of a [PdfDocument] — a non-suppressible trust caption above a swipeable
 * pager of rasterised pages. `PdfDocumentView` fills the bounds the consumer gives it and
 * does NOT assume a full screen: the caption takes its natural height, the pager takes
 * the rest, and each page is letterboxed into the pager slot rather than sized from a
 * fixed aspect ratio — so a short consumer slot can never make a page overflow upward
 * into the caption. The trust caption is composed on the consumer's own background;
 * only the pager carries [is.walt.passes.document.ui.theme.DocumentSemantics.laneBackground],
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
 *    `DocumentPublicApiSurfaceTest.passesDocumentUiCompiledClassesContainNoForbiddenStrings`
 *    enforces by scanning compiled bytecode for `android.intent.action.SEND` and the
 *    `application/pdf` MIME literal so a future contributor cannot quietly add either.
 *  - Inline surface is fixed 1x: no pinch-zoom, no pan, no double-tap. Zoom lives only
 *    on the full-screen detail surface (wpass-ny4 / wpass-jil).
 *
 * @param fullScreenAffordance Optional host-supplied open-full-screen affordance, used
 *   only when [onOpenFullScreen] is also non-null. Mirrors the `closeButton` slot on
 *   [FullScreenDocumentView]: the host owns chrome — shape, corner radius, leading icon,
 *   label, padding (wpass-emn). When supplied it is **floated over the bottom-centre of
 *   the page** (matching Walt's pill design, which overlaps the page edge); the host
 *   accepts that overlap as part of choosing a floating affordance. When `null` (the
 *   default) the kernel's neutral banner is **docked below the page** exactly as before,
 *   so consumers that do not pass this slot are unchanged and the page is never obscured.
 *   Either way this slot cannot suppress the trust caption or the page tap target; both
 *   are structural and independent of it.
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
private fun PdfDocumentView(
    doc: PdfDocument,
    pdfFile: ParcelFileDescriptor,
    renderer: PdfRendererBinder,
    modifier: Modifier = Modifier,
    telemetry: DocumentTelemetryGuard = DocumentTelemetryGuard.NoOp,
    trustCaption: TrustCaptionPlacement = TrustCaptionPlacement.Docked,
    onOpenFullScreen: (() -> Unit)? = null,
    fullScreenAffordance: (@Composable (onOpen: () -> Unit) -> Unit)? = null,
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
        // Docked: the kernel renders the verbatim caption here. HostedTypeRow: the kernel
        // renders nothing — the host carries provenance via its own "Pass type" details
        // row (wpass-gv6 / D5 concession). Exhaustive `when` (not an `if`) so a future
        // placement arm cannot silently fall through to omitting the caption — the one
        // direction a trust surface must never default to.
        when (trustCaption) {
            TrustCaptionPlacement.Docked -> DocumentTrustCaption()
            TrustCaptionPlacement.HostedTypeRow -> Unit
        }

        // `laneBackground` paints behind the pager only — the document-surface tone the
        // page sits on (showing through ContentScale.Fit letterbox bars). The page region
        // is a Box so a host-supplied affordance can float over its bottom edge.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(semantics.laneBackground.toComposeColor()),
        ) {
            // Page tap opens full screen; clickable and the pager's drag coexist (Compose
            // routes quick press-release to the click, horizontal drag to the pager).
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

            // A host-supplied affordance floats over the page bottom-centre (wpass-emn);
            // the host accepts that overlap by choosing a floating affordance.
            if (onOpenFullScreen != null && fullScreenAffordance != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                ) {
                    fullScreenAffordance(onOpenFullScreen)
                }
            }
        }

        // No custom affordance: the neutral banner docks below the page (original
        // layout), so default consumers' page content is never obscured. The trust
        // caption above this Column cannot be pushed off-screen by it.
        if (onOpenFullScreen != null && fullScreenAffordance == null) {
            FullScreenBanner(onClick = onOpenFullScreen)
        }
    }
}

/**
 * Presentation of an [ImageDocument] — the non-suppressible trust caption above a single,
 * fixed-fit image (wpass-i9x step 4). The image-arm analogue of [PdfDocumentView], minus the
 * pager: an image is a single page, so there is no [HorizontalPager] and no per-page cache.
 * The original image is decoded once inside the `passes-image` sandbox (reached through the
 * caller-supplied [decoder], the `ImageDecodeBinder` interface, never the concrete client) and
 * the bounded ARGB_8888 raster is letterboxed into the slot with [ContentScale.Fit].
 *
 * Trust contract (identical to the PDF arm):
 *
 *  - The non-suppressible [DocumentTrustCaption] is composed inside this view and gated by no
 *    parameter. There is no overload that omits it. `DocumentSurfaceLockTest` /
 *    `DocumentTrustSurfaceTest` pin the shape and the visible text across both arms.
 *  - The view displays only the decoded raster and the caption. ADR 0005 D4: no image
 *    metadata, no EXIF, no extracted text — the content description is a fixed neutral string.
 *  - No share / export / open-with affordance (ADR 0005 D8); the bytecode scan in
 *    `DocumentPublicApiSurfaceTest` keeps it that way.
 *  - Fixed 1x: no pinch-zoom or pan inline, matching the PDF inline surface.
 *
 * [imageFile] is the ORIGINAL image bytes, owned by the caller; it MUST stay open while this
 * view is composed. Close after `DocumentView` leaves composition. Full-screen zoom for images
 * is intentionally out of scope here (the PDF `FullScreenDocumentView` is unchanged); the inline
 * surface is the image-document presentation this step ships.
 */
@Composable
@Suppress("LongParameterList")
private fun ImageDocumentView(
    documentId: DocumentId,
    imageFile: ParcelFileDescriptor,
    decoder: ImageDecodeBinder,
    modifier: Modifier = Modifier,
    telemetry: DocumentTelemetryGuard = DocumentTelemetryGuard.NoOp,
    trustCaption: TrustCaptionPlacement = TrustCaptionPlacement.Docked,
) {
    val semantics = LocalDocumentSemantics.current
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Docked: the kernel renders the verbatim caption here. HostedTypeRow: the kernel
        // renders nothing — the host carries provenance via its own "Pass type" details
        // row (wpass-gv6 / D5 concession). Exhaustive `when` (not an `if`) so a future
        // placement arm cannot silently fall through to omitting the caption — the one
        // direction a trust surface must never default to.
        when (trustCaption) {
            TrustCaptionPlacement.Docked -> DocumentTrustCaption()
            TrustCaptionPlacement.HostedTypeRow -> Unit
        }

        // `laneBackground` paints behind the image only — the document-surface tone the image
        // sits on (showing through the ContentScale.Fit letterbox bars), so the caption above
        // reads as host chrome rather than part of the document surface, matching the PDF arm.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(semantics.laneBackground.toComposeColor()),
        ) {
            val density = LocalDensity.current
            val requestWidthPx = with(density) {
                TARGET_PAGE_WIDTH_DP.dp.toPx().toInt().coerceAtLeast(1)
            }
            val requestHeightPx = with(density) {
                TARGET_PAGE_HEIGHT_DP.dp.toPx().toInt().coerceAtLeast(1)
            }
            val state = rememberDocumentImage(
                documentId = documentId,
                imageFile = imageFile,
                decoder = decoder,
                targetSizePx = IntSize(requestWidthPx, requestHeightPx),
                telemetry = telemetry,
            )
            // Loading / Failed render nothing inline; the lane tone is the placeholder, matching
            // DocumentPage. A future retry affordance belongs on DocumentTile, not here.
            when (state) {
                is DocumentImageState.Loading, is DocumentImageState.Failed -> Unit
                is DocumentImageState.Rendered -> Image(
                    bitmap = state.image,
                    // ADR 0005 D4 forbids extracting text/metadata from the image; a fixed
                    // neutral description is the only safe TalkBack fallback.
                    contentDescription = "Image document",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
                )
            }
        }
    }
}

// wpass-jil: the kernel's neutral default open-full-screen affordance, docked below the
// page. Label and colours come from DocumentSemantics; a host wanting a different
// register (pill, leading icon, floating placement) supplies its own via DocumentView's
// `fullScreenAffordance` slot (wpass-emn).
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
