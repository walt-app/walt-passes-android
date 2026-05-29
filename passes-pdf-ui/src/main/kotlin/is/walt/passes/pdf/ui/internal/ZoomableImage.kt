package `is`.walt.passes.pdf.ui.internal

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import `is`.walt.passes.pdf.android.RenderSourceRect
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Pinch-zoom + pan surface for a single bitmap. Originally inline in `DocumentView`
 * (`wpass-1wq`); relocated by `wpass-ny4` so the full-screen surface (`wpass-jil`) is
 * the only call site. Translation is clamped to the slot bounds so the bitmap cannot
 * be panned entirely off-screen.
 *
 * When [pageAspect] is supplied, [visiblePageSubRect] math accounts for ContentScale.Fit
 * letterbox bars in the bitmap so the emitted [RenderSourceRect.SubRect] matches the
 * region of the actual page the user pinched (`wpass-6ag` review C3).
 *
 * When [zoomedReplacement] is non-null and the user is not actively transforming, it
 * overrides the displayed image — the sub-rect render that the caller fetched in
 * response to the previous settle. The replacement carries the visible region's own
 * aspect ratio (the renderer rasterises it uniformly, `wpass-fdh`), so it is placed at
 * the on-screen box that region currently occupies via [visiblePageBoxOnScreen] rather
 * than stretched to fill the slot — laneBackground shows around it where the viewport
 * extends past the page edge. Starting a new gesture (or any double-tap) clears the
 * override through the caller's [onTransformStarted] callback so a stale region is
 * never shown against a changed transform.
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
internal fun ZoomableImage(
    bitmap: ImageBitmap,
    contentDescription: String,
    modifier: Modifier = Modifier,
    minScale: Float = DEFAULT_MIN_SCALE,
    maxScale: Float = DEFAULT_MAX_SCALE,
    doubleTapScale: Float = DEFAULT_DOUBLE_TAP_SCALE,
    pageAspect: Float? = null,
    zoomedReplacement: ImageBitmap? = null,
    onZoomedRegionChanged: ((RenderSourceRect) -> Unit)? = null,
    onTransformStarted: (() -> Unit)? = null,
) {
    var scale by remember { mutableFloatStateOf(minScale) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var slotSize by remember { mutableStateOf(IntSize.Zero) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(minScale, maxScale)
        val maxX = ((newScale - 1f) * slotSize.width / 2f).coerceAtLeast(0f)
        val maxY = ((newScale - 1f) * slotSize.height / 2f).coerceAtLeast(0f)
        val proposed = offset + panChange
        offset = Offset(
            proposed.x.coerceIn(-maxX, maxX),
            proposed.y.coerceIn(-maxY, maxY),
        )
        scale = newScale
        if (newScale <= minScale) offset = Offset.Zero
    }

    // Fire onTransformStarted exactly on the false -> true edge of in-progress, and
    // onZoomedRegionChanged exactly on the true -> false edge with a settled zoom.
    // snapshotFlow + distinctUntilChanged means recomposition without a gesture-state
    // change does not re-fire either callback (`wpass-6ag` review M3).
    if (onZoomedRegionChanged != null || onTransformStarted != null) {
        LaunchedEffect(transformableState) {
            snapshotFlow { transformableState.isTransformInProgress }
                .distinctUntilChanged()
                .collect { inProgress ->
                    if (inProgress) {
                        onTransformStarted?.invoke()
                    } else if (scale > minScale && slotSize != IntSize.Zero) {
                        onZoomedRegionChanged?.invoke(
                            visiblePageSubRect(scale, offset, slotSize, pageAspect),
                        )
                    }
                }
        }
    }

    val density = LocalDensity.current
    val transforming = transformableState.isTransformInProgress
    // Only honour the replacement while genuinely zoomed in. A double-tap reset drops
    // scale to minScale without a transform edge, so this gate (not just !transforming)
    // is what keeps a stale sub-rect from being shown over the reset full page.
    val replacementBox = if (zoomedReplacement != null && !transforming && scale > minScale) {
        visiblePageBoxOnScreen(scale, offset, slotSize, pageAspect)
    } else {
        null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { slotSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        // Any double-tap changes the transform; drop the stale sub-rect
                        // so it is never displayed against the new scale/offset.
                        onTransformStarted?.invoke()
                        if (scale > minScale) {
                            scale = minScale
                            offset = Offset.Zero
                        } else {
                            scale = doubleTapScale
                            offset = Offset.Zero
                            // Double-tap raises scale without a transform edge, so the
                            // snapshotFlow never fires; request the sharp sub-rect render
                            // explicitly to keep wpass-f4b parity with the pinch path.
                            if (slotSize != IntSize.Zero) {
                                onZoomedRegionChanged?.invoke(
                                    visiblePageSubRect(doubleTapScale, Offset.Zero, slotSize, pageAspect),
                                )
                            }
                        }
                    },
                )
            }
            .transformable(state = transformableState, canPan = { scale > minScale }),
        contentAlignment = Alignment.Center,
    ) {
        if (replacementBox != null) {
            // The replacement is the visible page region rasterised at viewport
            // resolution with its native aspect ratio. Place it at the on-screen box
            // that region occupies; FillBounds is exact because the box and the bitmap
            // share that aspect, so no axis is independently scaled (`wpass-fdh`).
            Image(
                bitmap = zoomedReplacement!!,
                contentDescription = contentDescription,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(replacementBox.left.roundToInt(), replacementBox.top.roundToInt()) }
                    .size(
                        with(density) { replacementBox.width.toDp() },
                        with(density) { replacementBox.height.toDp() },
                    ),
            )
        } else {
            Image(
                bitmap = bitmap,
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
}

/**
 * Slot-pixel rectangle of the page region the viewport currently shows. [page] is the
 * on-screen page rect (slot minus ContentScale.Fit letterbox); `i*` is its intersection
 * with the visible window, both in the untransformed slot coordinate space. `null` when
 * the viewport sits entirely in the letterbox (zero-area intersection).
 */
private data class VisibleRegion(
    val ix0: Float,
    val iy0: Float,
    val ix1: Float,
    val iy1: Float,
    val page: PageRectInSlot,
    val visLeft: Float,
    val visTop: Float,
)

private fun visibleRegion(
    scale: Float,
    offset: Offset,
    slotSize: IntSize,
    pageAspect: Float?,
): VisibleRegion? {
    val slotW = slotSize.width.toFloat()
    val slotH = slotSize.height.toFloat()
    val page = pageRectInSlot(slotW, slotH, pageAspect)
    // Visible window in slot pixels at the current zoom: the slot mapped back through
    // the graphicsLayer transform.
    val visW = slotW / scale
    val visH = slotH / scale
    val visLeft = slotW / 2f - offset.x / scale - visW / 2f
    val visTop = slotH / 2f - offset.y / scale - visH / 2f
    val ix0 = maxOf(visLeft, page.left)
    val iy0 = maxOf(visTop, page.top)
    val ix1 = minOf(visLeft + visW, page.left + page.width)
    val iy1 = minOf(visTop + visH, page.top + page.height)
    if (ix1 <= ix0 || iy1 <= iy0) return null
    return VisibleRegion(ix0, iy0, ix1, iy1, page, visLeft, visTop)
}

/**
 * Maps the currently-visible region of the displayed page into a normalised page rect
 * for the renderer's [RenderSourceRect.SubRect]. When [pageAspect] is null the slot is
 * treated as filling the page (legacy behaviour from the inline surface).
 */
@Suppress("ReturnCount")
internal fun visiblePageSubRect(
    scale: Float,
    offset: Offset,
    slotSize: IntSize,
    pageAspect: Float?,
): RenderSourceRect.SubRect {
    // Pan entirely into letterbox zero-areas the intersection; the renderer rejects
    // zero-area rects, so emit a tiny centred fallback.
    val r = visibleRegion(scale, offset, slotSize, pageAspect)
        ?: return RenderSourceRect.SubRect(0.49f, 0.49f, 0.51f, 0.51f)
    val nLeft = ((r.ix0 - r.page.left) / r.page.width).coerceIn(0f, 1f)
    val nTop = ((r.iy0 - r.page.top) / r.page.height).coerceIn(0f, 1f)
    val nRight = ((r.ix1 - r.page.left) / r.page.width).coerceIn(0f, 1f)
    val nBottom = ((r.iy1 - r.page.top) / r.page.height).coerceIn(0f, 1f)
    if (nRight <= nLeft || nBottom <= nTop) {
        return RenderSourceRect.SubRect(0.49f, 0.49f, 0.51f, 0.51f)
    }
    return RenderSourceRect.SubRect(nLeft, nTop, nRight, nBottom)
}

/**
 * On-screen (viewport-pixel) box the visible page region occupies. Derived from the same
 * intersection as [visiblePageSubRect], so the box always has the rendered region's
 * aspect ratio — placing the (aspect-faithful) replacement bitmap here can never stretch
 * it. `null` when the viewport sits entirely in the letterbox.
 */
internal fun visiblePageBoxOnScreen(
    scale: Float,
    offset: Offset,
    slotSize: IntSize,
    pageAspect: Float?,
): ScreenBox? {
    val r = visibleRegion(scale, offset, slotSize, pageAspect) ?: return null
    // The visible window [visLeft, visTop, +visW, +visH] scales by `scale` to fill the
    // slot, so a base-space point bx maps to screen (bx - visLeft) * scale.
    return ScreenBox(
        left = (r.ix0 - r.visLeft) * scale,
        top = (r.iy0 - r.visTop) * scale,
        width = (r.ix1 - r.ix0) * scale,
        height = (r.iy1 - r.iy0) * scale,
    )
}

internal data class ScreenBox(val left: Float, val top: Float, val width: Float, val height: Float)

private data class PageRectInSlot(val left: Float, val top: Float, val width: Float, val height: Float)

private fun pageRectInSlot(slotW: Float, slotH: Float, pageAspect: Float?): PageRectInSlot {
    if (pageAspect == null || !pageAspect.isFinite() || pageAspect <= 0f) {
        return PageRectInSlot(0f, 0f, slotW, slotH)
    }
    val slotAspect = slotW / slotH
    return if (pageAspect > slotAspect) {
        val h = slotW / pageAspect
        PageRectInSlot(0f, (slotH - h) / 2f, slotW, h)
    } else {
        val w = slotH * pageAspect
        PageRectInSlot((slotW - w) / 2f, 0f, w, slotH)
    }
}

// Sub-rect re-render lands the sharp-at-zoom property (`wpass-f4b`), so MAX_SCALE can
// sit at 5f — the renderer rasterises the visible region at viewport resolution within
// the unchanged 4 MP per-bitmap cap, so the user-perceived pixel density at 5x is the
// same as at 1x rather than 5x-bilinear-smeared.
internal const val DEFAULT_MIN_SCALE: Float = 1f
internal const val DEFAULT_MAX_SCALE: Float = 5f
internal const val DEFAULT_DOUBLE_TAP_SCALE: Float = 2f
