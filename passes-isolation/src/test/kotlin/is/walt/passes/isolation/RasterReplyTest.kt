package `is`.walt.passes.isolation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RasterReplyTest {
    @Suppress("LongParameterList")
    private fun fits(
        widthPx: Int = 100,
        heightPx: Int = 50,
        maxWidthPx: Int = 100,
        maxHeightPx: Int = 50,
        maxPixels: Long = 5_000,
        bufferBytes: Long = 20_000,
    ) = rasterReplyFits(widthPx, heightPx, maxWidthPx, maxHeightPx, maxPixels, bufferBytes)

    @Test
    fun exactFitIsAccepted() {
        assertThat(fits()).isTrue()
        assertThat(fits(widthPx = 10, heightPx = 10)).isTrue()
    }

    @Test
    fun nonPositiveDimsAreRefused() {
        assertThat(fits(widthPx = 0)).isFalse()
        assertThat(fits(heightPx = -1)).isFalse()
    }

    @Test
    fun dimsPastTheRequestedBoundAreRefused() {
        assertThat(fits(widthPx = 101, heightPx = 1)).isFalse()
        assertThat(fits(widthPx = 1, heightPx = 51)).isFalse()
    }

    @Test
    fun pixelCountPastTheCapIsRefusedEvenInsideTheRequestedBound() {
        assertThat(fits(maxPixels = 4_999)).isFalse()
    }

    @Test
    fun bufferSmallerThanTheClaimedRasterIsRefused() {
        assertThat(fits(bufferBytes = 19_999)).isFalse()
    }

    @Test
    fun hugeClaimDoesNotOverflow() {
        val max = Int.MAX_VALUE
        assertThat(rasterReplyFits(max, max, max, max, Long.MAX_VALUE, Long.MAX_VALUE)).isFalse()
        assertThat(rasterReplyFits(20_000, 20_000, max, max, 4L * 1024 * 1024, 4_096)).isFalse()
    }
}
