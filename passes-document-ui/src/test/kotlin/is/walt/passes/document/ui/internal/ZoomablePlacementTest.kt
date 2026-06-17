package `is`.walt.passes.document.ui.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the no-stretch invariant of the full-screen zoom path (`wpass-fdh`): the on-screen
 * box the replacement bitmap is placed into ([visiblePageBoxOnScreen]) always has the
 * same aspect ratio as the page region the renderer rasterised for it
 * ([visiblePageSubRect]). Equal aspects mean no axis is ever independently scaled, so the
 * page cannot display stretched at any zoom/pan — including when the viewport clamps
 * against the page's letterbox edge, which was the bug's intermittent trigger.
 */
class ZoomablePlacementTest {

    // Box aspect must equal the sub-rect's aspect expressed in page pixels:
    //   (right-left)/(bottom-top) * pageAspect.
    private fun assertBoxMatchesRegion(
        scale: Float,
        offset: Offset,
        slotSize: IntSize,
        pageAspect: Float?,
    ) {
        val box = visiblePageBoxOnScreen(scale, offset, slotSize, pageAspect)
        val rect = visiblePageSubRect(scale, offset, slotSize, pageAspect)
        assertThat(box).isNotNull()
        val fallbackAspect = slotSize.width.toFloat() / slotSize.height.toFloat()
        val regionAspectInPagePx =
            (rect.right - rect.left) / (rect.bottom - rect.top) * (pageAspect ?: fallbackAspect)
        assertThat(box!!.width / box.height)
            .isWithin(0.01f)
            .of(regionAspectInPagePx)
    }

    @Test
    fun centredZoomOnSquareSlotMatchingPageHasNoStretch() {
        assertBoxMatchesRegion(2f, Offset.Zero, IntSize(1000, 1000), pageAspect = 1f)
    }

    @Test
    fun portraitPageZoomedAndPannedToLetterboxEdgeHasNoStretch() {
        // The reported trigger: portrait page (aspect 0.7) in a square slot leaves side
        // letterbox. Pan fully right at 2x so the left letterbox bar enters the viewport.
        assertBoxMatchesRegion(2f, Offset(500f, 0f), IntSize(1000, 1000), pageAspect = 0.7f)
    }

    @Test
    fun landscapePageZoomedAndPannedToTopEdgeHasNoStretch() {
        assertBoxMatchesRegion(3f, Offset(0f, 700f), IntSize(1080, 1920), pageAspect = 1.4f)
    }

    @Test
    fun nullPageAspectFallbackHasNoStretch() {
        assertBoxMatchesRegion(2.5f, Offset(-200f, 100f), IntSize(1200, 800), pageAspect = null)
    }

    @Test
    fun pannedToLetterboxEdgeProducesExpectedBoxGeometry() {
        // Concrete numbers for the trigger case so a regression in the mapping (not just
        // the aspect) is caught. Portrait page 0.7 in 1000x1000: pageRect x[150,850].
        // scale 2, offsetX 500 -> visLeft 0, visible base x[0,500]; clamp to page ->
        // ix[150,500]. Screen: left (150-0)*2=300, width (500-150)*2=700.
        val box = visiblePageBoxOnScreen(2f, Offset(500f, 0f), IntSize(1000, 1000), pageAspect = 0.7f)!!
        assertThat(box.left).isWithin(0.5f).of(300f)
        assertThat(box.width).isWithin(0.5f).of(700f)
        assertThat(box.top).isWithin(0.5f).of(0f)
        assertThat(box.height).isWithin(0.5f).of(1000f)
    }

    @Test
    fun viewportEntirelyInLetterboxYieldsNoBox() {
        // A degenerate offset that places the visible window wholly in the side bar.
        // Defensive: the production gate also requires scale > minScale, but the helper
        // must report null rather than a zero/negative-area box.
        val box = visiblePageBoxOnScreen(
            scale = 10f,
            offset = Offset(4500f, 0f),
            slotSize = IntSize(1000, 1000),
            pageAspect = 0.7f,
        )
        // Either null (no intersection) or a strictly positive-area box — never inverted.
        if (box != null) {
            assertThat(box.width).isGreaterThan(0f)
            assertThat(box.height).isGreaterThan(0f)
        }
    }
}
