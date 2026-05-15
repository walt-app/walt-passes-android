package `is`.walt.passes.pdf.ui.internal

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.IntSize
import `is`.walt.passes.pdf.android.RenderSourceRect
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Pinch-zoom + pan surface for a single bitmap. Originally inline in `DocumentView`
 * (`wpass-1wq`); relocated by `wpass-ny4` so the full-screen surface (`wpass-jil`) is
 * the only call site. Translation is clamped to the slot bounds so the bitmap cannot
 * be panned entirely off-screen.
 *
 * When [pageAspect] is supplied, [visibleRect] math accounts for ContentScale.Fit
 * letterbox bars in the bitmap so the emitted [RenderSourceRect.SubRect] matches the
 * region of the actual page the user pinched (`wpass-6ag` review C3).
 *
 * When [zoomedReplacement] is non-null and the user is not actively transforming, it
 * overrides the displayed image — the sub-rect render that the caller fetched in
 * response to the previous settle. Starting a new gesture clears the override (the
 * caller's [onTransformStarted] callback lets it drop the stale bitmap).
 */
@Composable
@Suppress("LongParameterList", "LongMethod")
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
                            visibleRect(scale, offset, slotSize, pageAspect),
                        )
                    }
                }
        }
    }

    val transforming = transformableState.isTransformInProgress
    val displayReplacement = zoomedReplacement != null && !transforming

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { slotSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > minScale) {
                            scale = minScale
                            offset = Offset.Zero
                        } else {
                            scale = doubleTapScale
                        }
                    },
                )
            }
            .transformable(state = transformableState, canPan = { scale > minScale }),
        contentAlignment = Alignment.Center,
    ) {
        if (displayReplacement) {
            // The replacement is already the visible region rasterised at viewport
            // resolution; draw it filling the slot with no transform.
            Image(
                bitmap = zoomedReplacement!!,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
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
 * Maps the currently-visible region of the displayed page into a normalised page rect.
 *
 * When [pageAspect] is supplied, the helper computes the on-screen page rect inside the
 * slot (slot minus ContentScale.Fit letterbox bars), maps the visible window into that
 * rect's coordinates, then normalises. When [pageAspect] is null the slot is treated
 * as filling the page (legacy behaviour from the inline surface).
 */
@Suppress("ReturnCount")
private fun visibleRect(
    scale: Float,
    offset: Offset,
    slotSize: IntSize,
    pageAspect: Float?,
): RenderSourceRect.SubRect {
    val slotW = slotSize.width.toFloat()
    val slotH = slotSize.height.toFloat()
    val pageOnScreen = pageRectInSlot(slotW, slotH, pageAspect)
    // Visible window in slot pixels at the current zoom: the slot mapped back through
    // the graphicsLayer transform.
    val visW = slotW / scale
    val visH = slotH / scale
    val visLeft = slotW / 2f - offset.x / scale - visW / 2f
    val visTop = slotH / 2f - offset.y / scale - visH / 2f
    val ix0 = maxOf(visLeft, pageOnScreen.left)
    val iy0 = maxOf(visTop, pageOnScreen.top)
    val ix1 = minOf(visLeft + visW, pageOnScreen.left + pageOnScreen.width)
    val iy1 = minOf(visTop + visH, pageOnScreen.top + pageOnScreen.height)
    val nLeft = ((ix0 - pageOnScreen.left) / pageOnScreen.width).coerceIn(0f, 1f)
    val nTop = ((iy0 - pageOnScreen.top) / pageOnScreen.height).coerceIn(0f, 1f)
    val nRight = ((ix1 - pageOnScreen.left) / pageOnScreen.width).coerceIn(0f, 1f)
    val nBottom = ((iy1 - pageOnScreen.top) / pageOnScreen.height).coerceIn(0f, 1f)
    // Pan entirely into letterbox would zero-area the intersection; the renderer
    // rejects zero-area rects, so emit a tiny centred fallback.
    if (nRight <= nLeft || nBottom <= nTop) {
        return RenderSourceRect.SubRect(0.49f, 0.49f, 0.51f, 0.51f)
    }
    return RenderSourceRect.SubRect(nLeft, nTop, nRight, nBottom)
}

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
