package `is`.walt.passes.pdf.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the uniform-scale contract of the sub-rect renderer (`wpass-fdh`). The output
 * bitmap must carry the sub-rect's own page-pixel aspect ratio so the page is never
 * stretched at the source. The pre-fix code scaled X and Y independently to fill a
 * fixed bitmap; every assertion here would fail against that.
 */
class SubRectOutputDimsTest {

    private fun assertAspectPreserved(
        rect: RenderSourceRect.SubRect,
        pageWidth: Int,
        pageHeight: Int,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ) {
        val dims = subRectOutputDims(rect, pageWidth, pageHeight, maxWidthPx, maxHeightPx)
        val srcWidth = (rect.right - rect.left) * pageWidth
        val srcHeight = (rect.bottom - rect.top) * pageHeight
        // The defining no-stretch property: output aspect == region aspect (within the
        // sub-pixel rounding of integer bitmap dimensions).
        assertThat(dims.widthPx.toFloat() / dims.heightPx.toFloat())
            .isWithin(0.02f)
            .of(srcWidth / srcHeight)
        // Never exceeds the requested bound (which the caller clamped under the 4 MP cap).
        assertThat(dims.widthPx).isAtMost(maxWidthPx)
        assertThat(dims.heightPx).isAtMost(maxHeightPx)
        assertThat(dims.widthPx).isAtLeast(1)
        assertThat(dims.heightPx).isAtLeast(1)
    }

    @Test
    fun wideRegionOnSquarePagePreservesAspect() {
        assertAspectPreserved(
            RenderSourceRect.SubRect(0f, 0f, 1f, 0.5f),
            pageWidth = 1000, pageHeight = 1000, maxWidthPx = 800, maxHeightPx = 800,
        )
    }

    @Test
    fun tallRegionOnSquarePagePreservesAspect() {
        assertAspectPreserved(
            RenderSourceRect.SubRect(0.25f, 0f, 0.5f, 1f),
            pageWidth = 1000, pageHeight = 1000, maxWidthPx = 800, maxHeightPx = 800,
        )
    }

    @Test
    fun offCentreRegionOnPortraitPagePreservesAspect() {
        assertAspectPreserved(
            RenderSourceRect.SubRect(0.1f, 0.1f, 0.6f, 0.4f),
            pageWidth = 850, pageHeight = 1100, maxWidthPx = 1080, maxHeightPx = 1920,
        )
    }

    @Test
    fun fullUnitRectMatchesPageAspect() {
        assertAspectPreserved(
            RenderSourceRect.SubRect(0f, 0f, 1f, 1f),
            pageWidth = 850, pageHeight = 1100, maxWidthPx = 720, maxHeightPx = 1280,
        )
    }

    @Test
    fun outputFitsWithinBoundAndScaleIsUniform() {
        // A region whose aspect forces the height bound to dominate; the chosen scale
        // must size the width strictly within the slot, not stretch to fill it.
        val dims = subRectOutputDims(
            RenderSourceRect.SubRect(0.4f, 0f, 0.6f, 1f),
            pageWidth = 1000, pageHeight = 1000, maxWidthPx = 800, maxHeightPx = 800,
        )
        // region 200x1000 (aspect 0.2): height-bound, so scale = 800/1000 = 0.8.
        assertThat(dims.scale).isWithin(1e-4f).of(0.8f)
        assertThat(dims.widthPx).isEqualTo(160)
        assertThat(dims.heightPx).isEqualTo(800)
    }
}
