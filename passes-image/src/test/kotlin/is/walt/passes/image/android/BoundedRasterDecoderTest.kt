package `is`.walt.passes.image.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Pins the pure halves of the bounded raster decode (wpass-6yp): the file-size cap
 * ([readBoundedBytes]), the header-cap decision table ([headerRejection]), the output-size
 * validation ([isOutputSizeValid]), and the aspect-preserving downscale math ([outputDims]).
 * The platform `ImageDecoder` integration these feed — that the header listener fires before
 * allocation and that OOM is contained — is the instrumented half; the policy that decides
 * accept-vs-reject and the scaling are exercised here on the JVM where they can be enumerated.
 */
class BoundedRasterDecoderTest {
    private val config = ImageDecodeConfig()

    // ------------------------------------------------------------------ size cap

    @Test
    fun readBoundedBytesReturnsAllBytesWhenUnderCap() {
        val bytes = ByteArray(1000) { (it % 251).toByte() }
        val read = readBoundedBytes(ByteArrayInputStream(bytes), maxBytes = 4096)
        assertThat(read).isNotNull()
        assertThat(read!!.toList()).isEqualTo(bytes.toList())
    }

    @Test
    fun readBoundedBytesReturnsExactlyAtCapBoundary() {
        val read = readBoundedBytes(ByteArrayInputStream(ByteArray(4096) { 1 }), maxBytes = 4096)
        assertThat(read).isNotNull()
        assertThat(read!!.size).isEqualTo(4096)
    }

    @Test
    fun readBoundedBytesRejectsOneByteOverCap() {
        val read = readBoundedBytes(ByteArrayInputStream(ByteArray(4097) { 1 }), maxBytes = 4096)
        assertThat(read).isNull()
    }

    @Test
    fun readBoundedBytesHandlesEmptyStream() {
        val read = readBoundedBytes(ByteArrayInputStream(ByteArray(0)), maxBytes = 4096)
        assertThat(read).isNotNull()
        assertThat(read!!.size).isEqualTo(0)
    }

    // ------------------------------------------------------------- header cap table

    @Test
    fun headerRejectionAcceptsAllowedFormatWithinCaps() {
        assertThat(headerRejection("image/png", width = 1024, height = 768, config = config)).isNull()
        assertThat(headerRejection("image/jpeg", width = 4000, height = 3000, config = config)).isNull()
        assertThat(headerRejection("image/webp", width = 8000, height = 6000, config = config)).isNull()
    }

    @Test
    fun headerRejectionRejectsDisallowedFormatAsNotAnImage() {
        assertThat(headerRejection("image/gif", width = 10, height = 10, config = config))
            .isEqualTo(ImageDecodeRejectedKind.NotAnImage)
        assertThat(headerRejection("image/svg+xml", width = 10, height = 10, config = config))
            .isEqualTo(ImageDecodeRejectedKind.NotAnImage)
        assertThat(headerRejection(null, width = 10, height = 10, config = config))
            .isEqualTo(ImageDecodeRejectedKind.NotAnImage)
    }

    @Test
    fun headerRejectionRejectsOverWideOrTallAsDimensionsTooLarge() {
        val overSide = config.maxDimensionPx + 1
        assertThat(headerRejection("image/png", width = overSide, height = 10, config = config))
            .isEqualTo(ImageDecodeRejectedKind.DimensionsTooLarge)
        assertThat(headerRejection("image/png", width = 10, height = overSide, config = config))
            .isEqualTo(ImageDecodeRejectedKind.DimensionsTooLarge)
    }

    @Test
    fun headerRejectionRejectsOverAreaAsDimensionsTooLarge() {
        // The decompression-bomb shape that stays under the per-side cap but multiplies to a
        // huge canvas: both sides legal, product over the megapixel cap.
        val side = config.maxDimensionPx
        assertThat(side.toLong() * side.toLong()).isGreaterThan(config.maxAreaPx)
        assertThat(headerRejection("image/png", width = side, height = side, config = config))
            .isEqualTo(ImageDecodeRejectedKind.DimensionsTooLarge)
    }

    @Test
    fun headerRejectionFormatCheckPrecedesSizeCheck() {
        assertThat(
            headerRejection("image/gif", width = config.maxDimensionPx + 1, height = 10, config = config),
        ).isEqualTo(ImageDecodeRejectedKind.NotAnImage)
    }

    // ------------------------------------------------------------- output-size guard

    @Test
    fun outputSizeValidAcceptsPositiveUnderCap() {
        assertThat(isOutputSizeValid(100, 100, config.maxOutputPixels)).isTrue()
    }

    @Test
    fun outputSizeValidRejectsNonPositive() {
        assertThat(isOutputSizeValid(0, 100, config.maxOutputPixels)).isFalse()
        assertThat(isOutputSizeValid(100, -1, config.maxOutputPixels)).isFalse()
    }

    @Test
    fun outputSizeValidRejectsOverCap() {
        // A caller asking for a raster past the output cap is refused before any allocation.
        val over = 3000
        assertThat(over.toLong() * over.toLong()).isGreaterThan(config.maxOutputPixels)
        assertThat(isOutputSizeValid(over, over, config.maxOutputPixels)).isFalse()
    }

    // ------------------------------------------------------------- downscale math

    @Test
    fun outputDimsNeverUpscalesASmallSource() {
        // A 50x40 source into a 1000x1000 bound stays 50x40 — the host scales up for display.
        val dims = outputDims(srcW = 50, srcH = 40, maxWidthPx = 1000, maxHeightPx = 1000)
        assertThat(dims).isEqualTo(OutputDims(50, 40))
    }

    @Test
    fun outputDimsScalesDownPreservingAspect() {
        // A 4000x2000 (2:1) source into a 1000x1000 bound fits to 1000x500, aspect preserved.
        val dims = outputDims(srcW = 4000, srcH = 2000, maxWidthPx = 1000, maxHeightPx = 1000)
        assertThat(dims).isEqualTo(OutputDims(1000, 500))
    }

    @Test
    fun outputDimsFitsToTheTighterAxis() {
        // A tall 1000x4000 source into a 500x500 bound fits to 125x500 (height is the binding
        // axis), never exceeding either bound.
        val dims = outputDims(srcW = 1000, srcH = 4000, maxWidthPx = 500, maxHeightPx = 500)
        assertThat(dims).isEqualTo(OutputDims(125, 500))
        assertThat(dims.widthPx).isAtMost(500)
        assertThat(dims.heightPx).isAtMost(500)
    }

    @Test
    fun outputDimsClampsToAtLeastOnePixel() {
        // An extreme aspect ratio whose scaled minor axis rounds toward zero is clamped to 1px
        // rather than producing a zero-dimension (un-allocatable) bitmap.
        val dims = outputDims(srcW = 10_000, srcH = 1, maxWidthPx = 10, maxHeightPx = 10)
        assertThat(dims.widthPx).isAtLeast(1)
        assertThat(dims.heightPx).isAtLeast(1)
    }
}
