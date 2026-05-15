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

/**
 * Pinch-zoom + pan surface for a single bitmap. Originally inline in `DocumentView`
 * (`wpass-1wq`); relocated by `wpass-ny4` so the full-screen surface (`wpass-jil`) is
 * the only call site. Translation is clamped to the slot bounds so the user cannot
 * pan the bitmap entirely off-screen.
 *
 * When [onZoomedRegionChanged] is provided, the surface emits the currently-visible
 * sub-rect (in normalised page coordinates) after each gesture settles. The full-screen
 * surface uses this to fire a `wpass-f4b` sub-rect re-render so the visible region
 * stays sharp under zoom.
 */
@Composable
@Suppress("LongParameterList")
internal fun ZoomableImage(
    bitmap: ImageBitmap,
    contentDescription: String,
    modifier: Modifier = Modifier,
    minScale: Float = DEFAULT_MIN_SCALE,
    maxScale: Float = DEFAULT_MAX_SCALE,
    doubleTapScale: Float = DEFAULT_DOUBLE_TAP_SCALE,
    onZoomedRegionChanged: ((RenderSourceRect) -> Unit)? = null,
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

    // Emit the visible sub-rect once the gesture settles. `transformableState.isTransformInProgress`
    // is a snapshot read, so wrapping it in LaunchedEffect makes the emission happen exactly when
    // the user lifts their fingers. Skipped at fit (scale == 1) since the FullPage render already
    // covers it.
    if (onZoomedRegionChanged != null) {
        LaunchedEffect(transformableState.isTransformInProgress, scale, offset, slotSize) {
            if (!transformableState.isTransformInProgress && scale > minScale && slotSize != IntSize.Zero) {
                onZoomedRegionChanged(visibleRect(scale, offset, slotSize))
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { slotSize = it }
            // Keyed on Unit so the gesture detector survives every zoom step; the
            // handler closes over the mutable `scale` and re-reads it on each callback.
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

// At scale S, the visible window covers 1/S of the slot in each axis. The pan offset
// is in slot-pixel coordinates after the graphicsLayer scale, so dividing by `S *
// slotSize` normalises it. The slot extent is assumed to match the page extent at fit;
// in the full-screen surface the page is letterboxed via ContentScale.Fit so this is a
// best-effort approximation when the page's aspect ratio differs from the slot's.
private fun visibleRect(scale: Float, offset: Offset, slotSize: IntSize): RenderSourceRect.SubRect {
    val w = slotSize.width.toFloat()
    val h = slotSize.height.toFloat()
    val visibleFractionX = 1f / scale
    val visibleFractionY = 1f / scale
    val centerX = 0.5f - offset.x / (scale * w)
    val centerY = 0.5f - offset.y / (scale * h)
    val left = (centerX - visibleFractionX / 2f).coerceIn(0f, 1f - visibleFractionX)
    val top = (centerY - visibleFractionY / 2f).coerceIn(0f, 1f - visibleFractionY)
    return RenderSourceRect.SubRect(
        left = left,
        top = top,
        right = left + visibleFractionX,
        bottom = top + visibleFractionY,
    )
}

// Defaults match the original inline zoom surface (`wpass-1wq`). Hold MAX at 3f until
// `wpass-f4b`'s sub-rect re-render is wired in by the full-screen surface; once that
// lands the cap can move to 5f without producing pixelation past about 1.3-1.5x.
internal const val DEFAULT_MIN_SCALE: Float = 1f
internal const val DEFAULT_MAX_SCALE: Float = 3f
internal const val DEFAULT_DOUBLE_TAP_SCALE: Float = 2f
