package `is`.walt.passes.barcode

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.aztec.encoder.Encoder
import com.google.zxing.common.BitMatrix
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.ScannableFormat
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The wpass-pl7.2 ladder: [decodeLuminance] tries several scales of one image, because a single
 * attempt at the resolution the picker delivers is measurably not enough.
 *
 * Fixtures are synthesised here rather than committed as images, for the same two reasons as
 * [ZxingBarcodeSymbolDecoderTest]: the suite stays device-free, and no real boarding pass (whose
 * payload carries passenger name and PNR) enters the repository. [screenshot] reproduces the
 * reported repro's shape — a large Aztec carrying per-pixel screenshot/compression noise on a
 * 1080x2340 phone canvas — which fails at native resolution and reads once area-averaged down.
 * [photo] is the opposite shape and the reason the ladder keeps a near-native rung: a small
 * symbol inside a 4000x3000 camera photo, which the downscale rungs destroy.
 *
 * Both shapes were found by measurement (the sweep recorded on wpass-pl7.2), not by intuition:
 * decodability is not monotonic in scale, so these fixtures pin the ladder that was measured to
 * cover both, and any future edit to [DecodeLadder.STILL_IMAGE] has to keep clearing them.
 */
class DecodeLadderTest {
    @Test
    fun noisyScreenshotFailsAtNativeResolution() {
        // Establishes that the fixture reproduces the reported defect rather than passing
        // trivially: this is the pre-ladder behaviour, and it is what the live path still runs.
        assertThat(decodeLuminance(screenshot(), DecodeLadder.SINGLE_ATTEMPT))
            .isEqualTo(BarcodeDecodeResult.NoBarcodeFound)
    }

    @Test
    fun noisyScreenshotDecodesThroughTheLadder() {
        assertThat(decodeLuminance(screenshot()))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode(PAYLOAD, ScannableFormat.Aztec))
    }

    @Test
    fun smallSymbolInALargePhotoSurvivesTheLadder() {
        // The downscale rungs alone would lose this one; the capped near-native rung is what
        // keeps the ladder from trading one broken shape for another.
        assertThat(decodeLuminance(photo()))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode(PAYLOAD, ScannableFormat.Aztec))
    }

    @Test
    fun ladderStopsAtTheFirstRungThatDecodes() {
        val counted = CountingSource(screenshot())

        assertThat(decodeLuminance(counted)).isInstanceOf(BarcodeDecodeResult.DecodedBarcode::class.java)
        // One read, for the downscale the ladder derives its rungs from. The last rung IS the
        // source, so a ladder that kept climbing after decoding would hand the source itself to
        // the binarizer and read it a second time.
        assertThat(counted.matrixReads).isEqualTo(1)
    }

    @Test
    fun sourceBelowEveryCapIsDecodedExactlyOnce() {
        // The live-camera invariant: a 640x480 analysis frame collapses every rung onto the
        // source, so the ladder cannot silently multiply per-frame cost (wpass-pl7.3).
        assertThat(rungSizes(640, 480, DecodeLadder.STILL_IMAGE)).containsExactly(RungSize(640, 480))
    }

    @Test
    fun rungsArePickedFromTheLongestSideAndKeepAspectRatio() {
        assertThat(rungSizes(1080, 2340, DecodeLadder.STILL_IMAGE))
            .containsExactly(RungSize(738, 1600), RungSize(472, 1024), RungSize(1080, 2340))
            .inOrder()
    }

    @Test
    fun theLargestRungIsCappedSoABombIsNotDecodedAtFullArea() {
        // 50 MP is what the caller's header caps allow through. Capping the last rung is what
        // makes the ladder cheaper than the single uncapped attempt it replaces.
        val rungs = rungSizes(12_000, 4_166, DecodeLadder.STILL_IMAGE)

        assertThat(rungs.maxOf { it.width.toLong() * it.height }).isLessThan(6_000_000L)
    }

    @Test
    fun singleAttemptNeverResamples() {
        assertThat(rungSizes(4000, 3000, DecodeLadder.SINGLE_ATTEMPT)).containsExactly(RungSize(4000, 3000))
    }

    @Test
    fun anExhaustedBudgetStopsTheLadderAfterTheFirstRung() {
        // A device slower than the one the caps were measured on must degrade to "no barcode",
        // not to a decode its caller's watchdog kills. Only the first (downscaled) rung runs, and
        // this fixture needs a later one, so the budget is observable in the result.
        val impatient = DecodeLadder.STILL_IMAGE.withBudget(1.milliseconds)

        assertThat(decodeLuminance(photo(), impatient)).isEqualTo(BarcodeDecodeResult.NoBarcodeFound)
    }

    @Test
    fun theFirstRungAlwaysRunsEvenOnASpentBudget() {
        assertThat(decodeLuminance(screenshot(), DecodeLadder.STILL_IMAGE.withBudget(1.milliseconds)))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode(PAYLOAD, ScannableFormat.Aztec))
    }

    @Test
    fun aLadderWithoutRungsIsRejected() {
        assertThat(runCatching { DecodeLadder(emptyList(), 1.seconds) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun aNonPositiveBudgetIsRejected() {
        assertThat(runCatching { DecodeLadder(listOf(1600), (-1).milliseconds) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun rotatedOneDimensionalSymbolSurvivesEveryRungResampling() {
        // Every rung resamples once the source passes the largest cap, and a resampled rung that
        // does not support rotation costs OneDReader its 90-degree retry — silently, since the
        // upright case still passes. 12 MP phone photos (4032x3024, 4080x3072) are over that cap,
        // so this is the dominant camera input for the 1D loyalty-card case.
        val rotated = rotatedCode128(width = 4200, height = 3200)

        assertThat(decodeLuminance(rotated))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode(CODE_128_PAYLOAD, ScannableFormat.Code128))
    }

    @Test
    fun scaledRungsCarryZxingsOwnRotateAndCropSupport() {
        val scaled = scaledTo(ByteArray(16), srcWidth = 4, srcHeight = 4, width = 2, height = 2)

        assertThat(scaled.isRotateSupported).isTrue()
        assertThat(scaled.isCropSupported).isTrue()
    }

    @Test
    fun downscalingAveragesRatherThanPointSamples() {
        // Averaging is the property that recovers a noisy symbol; point-sampling would alias
        // thin bars away instead. A 2x2 checkerboard must flatten to its mean, not to one corner.
        val checker = byteArrayOf(0, 255.toByte(), 255.toByte(), 0)

        val scaled = scaledTo(checker, srcWidth = 2, srcHeight = 2, width = 1, height = 1)

        assertThat(scaled.matrix.single().toInt() and 0xFF).isEqualTo(127)
    }

    /** A 1080x2340 phone screenshot of a large, noisy Aztec — the reported repro's shape. */
    private fun screenshot(): RGBLuminanceSource = compose(width = 1080, height = 2340, modulePx = 20, noise = 90)

    /** A 4000x3000 camera photo of a small, clean Aztec — the shape downscaling would destroy. */
    private fun photo(): RGBLuminanceSource = compose(width = 4000, height = 3000, modulePx = 4, noise = 0)

    /** A Code 128 turned 90 degrees (bars running vertically) on a canvas past every rung's cap. */
    private fun rotatedCode128(
        width: Int,
        height: Int,
    ): RGBLuminanceSource {
        val symbol = MultiFormatWriter().encode(CODE_128_PAYLOAD, BarcodeFormat.CODE_128, 600, 200)
        val symbolWidth = symbol.height * MODULE_SCALE
        val symbolHeight = symbol.width * MODULE_SCALE
        val left = (width - symbolWidth) / 2
        val top = (height - symbolHeight) / 2
        val pixels = IntArray(width * height) { gray(255) }
        for (y in 0 until symbolHeight) {
            for (x in 0 until symbolWidth) {
                // The quarter turn: canvas (x, y) reads the symbol at (y, x).
                if (symbol.get(y / MODULE_SCALE, x / MODULE_SCALE)) {
                    pixels[(top + y) * width + (left + x)] = gray(0)
                }
            }
        }
        return RGBLuminanceSource(width, height, pixels)
    }

    /**
     * Centre an Aztec of [modulePx]-sized modules on a white canvas and perturb every pixel by up
     * to +/-[noise], standing in for the sensor and compression noise a real screenshot carries.
     * Deterministic: the seed is fixed so a fixture cannot pass or fail by luck.
     */
    private fun compose(
        width: Int,
        height: Int,
        modulePx: Int,
        noise: Int,
    ): RGBLuminanceSource {
        val symbol = SYMBOL
        val symbolWidth = symbol.width * modulePx
        val symbolHeight = symbol.height * modulePx
        val left = (width - symbolWidth) / 2
        val top = (height - symbolHeight) / 2
        val random = kotlin.random.Random(11)
        val pixels =
            IntArray(width * height) {
                gray(if (noise > 0) 255 - random.nextInt(noise + 1) else 255)
            }
        for (y in 0 until symbolHeight) {
            for (x in 0 until symbolWidth) {
                val level = if (symbol.get(x / modulePx, y / modulePx)) 0 else 255
                val perturbed = if (noise > 0) (level + random.nextInt(-noise, noise + 1)).coerceIn(0, 255) else level
                pixels[(top + y) * width + (left + x)] = gray(perturbed)
            }
        }
        return RGBLuminanceSource(width, height, pixels)
    }

    private fun gray(level: Int): Int = 0xFF shl 24 or (level shl 16) or (level shl 8) or level

    /** Counts how many times the reader materialised this source's grayscale — one read per rung. */
    private class CountingSource(private val delegate: LuminanceSource) :
        LuminanceSource(delegate.width, delegate.height) {
        var matrixReads: Int = 0
            private set

        override fun getRow(
            y: Int,
            row: ByteArray?,
        ): ByteArray = delegate.getRow(y, row)

        override fun getMatrix(): ByteArray {
            matrixReads++
            return delegate.matrix
        }
    }

    private companion object {
        /** Same length as the repro's IATA BCBP string; synthetic, so no passenger name or PNR. */
        val PAYLOAD: String = "M1WALT/TEST".padEnd(126, 'X')

        /** Encoded once at one module per pixel; [compose] scales it up to the fixture's size. */
        val SYMBOL: BitMatrix = Encoder.encode(PAYLOAD, 33, 0).matrix

        /** A 1D payload: four of the seven roster formats are 1D, and none reached a scaled rung. */
        const val CODE_128_PAYLOAD: String = "LOYALTY-ABC-123"

        /** Pixels per module for the 1D fixture, enough to survive the downscale rungs. */
        const val MODULE_SCALE: Int = 3
    }
}
