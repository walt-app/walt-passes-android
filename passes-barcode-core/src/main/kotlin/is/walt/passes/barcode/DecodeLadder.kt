package `is`.walt.passes.barcode

import com.google.zxing.GrayscaleLuminanceSource
import com.google.zxing.LuminanceSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How many views of one image [decodeLuminance] is allowed to try, and how long it may spend
 * doing so (wpass-pl7.2). A single full-resolution attempt is not enough: the reported
 * boarding-pass screenshot carries a valid Aztec that ZXing refuses at the picker's native
 * resolution and reads immediately once the image is area-averaged down, and the effect is not
 * monotonic in scale — measurement, not intuition, picks [rungsPx].
 *
 * Each rung is a cap on the longest side. A rung whose cap already exceeds the source is the
 * source itself (no resampling, no cost), so an image smaller than every cap runs exactly one
 * attempt — the pre-ladder behaviour, unchanged, which is what the live-camera path relies on.
 * Rungs producing a size an earlier rung already tried are skipped rather than decoded twice.
 *
 * ### Why the caps bound the work rather than widen it
 * The still-image caller kills its sandbox when a decode overruns (`DecodeWatchdog`, 5s), so a
 * ladder that simply appended attempts to the old native-resolution one would turn slow decodes
 * into killed processes. Two properties stop that:
 *  - **Cheap rungs first, and the largest rung is itself capped.** Cost stops scaling with the
 *    source's area. Measured over a no-barcode input, so every rung runs: a 50 MP canvas (the
 *    largest the caller's header caps admit) went from 875ms for the old single uncapped attempt
 *    to 262ms, and the ladder's worst case is now a 16 MP source at 495ms. Host-JVM figures — a
 *    phone is slower, which is what [budget] is for.
 *  - **[budget] bounds the tail.** Before starting a rung, its cost is predicted from the
 *    previous rung's measured cost scaled by the area ratio (decode cost is close to linear in
 *    pixels), and the rung is skipped if that prediction would not fit. Checking bare elapsed
 *    time instead would let the dearest rung start at the last instant and overrun by its whole
 *    duration — precisely the sandbox kill this design exists to avoid. So a device slower than
 *    the one the caps were measured on degrades to "no barcode found" rather than to a killed
 *    sandbox.
 *
 * The first rung always runs, whatever the budget: a ladder that could decline to look at all
 * would be a worse contract than the one it replaces. Its cost is bounded by construction
 * instead — the cheapest rung is first, and every cap is fixed.
 */
public class DecodeLadder internal constructor(
    public val rungsPx: List<Int>,
    public val budget: Duration,
) {
    init {
        require(rungsPx.isNotEmpty()) { "A ladder needs at least one rung." }
        require(rungsPx.all { it > 0 }) { "Rung caps must be positive (was $rungsPx)." }
        require(budget.isPositive()) { "Budget must be positive (was $budget)." }
    }

    /**
     * The same rungs on a different clock. The only knob a caller outside this module gets: the
     * budget is the caller's to set (it owns the watchdog the ladder has to finish inside), while
     * [rungsPx] is measured policy that belongs with the decode it was measured against.
     */
    public fun withBudget(budget: Duration): DecodeLadder = DecodeLadder(rungsPx, budget)

    public companion object {
        /** A rung that never resamples: decode the source at whatever size it arrives. */
        public const val NO_CAP: Int = Int.MAX_VALUE

        /**
         * The still-image ladder. Ordered cheapest-first so the common case pays the least and
         * [budget] drops the dearest rung rather than a cheap one.
         *
         * 1600 and 1024 are the two that actually recover the reported repro class — a large
         * symbol carrying screenshot/compression noise, where area-averaging is what makes it
         * readable. 4000 is the fallback for the opposite shape, a small symbol inside a large
         * photo, which the downscales destroy and which only a near-native view reads; it is
         * capped rather than native so a bomb-sized canvas cannot make this rung unbounded.
         * A 640 rung was measured and dropped: it recovered nothing 1024 had not already.
         */
        public val STILL_IMAGE: DecodeLadder = DecodeLadder(listOf(1600, 1024, 4000), 3.seconds)

        /**
         * One attempt at the source's own resolution — the pre-ladder behaviour, kept for the
         * live-camera path (`decodeYPlane`). A camera frame gets its retries from the next
         * frame, so paying for extra scales per frame would spend the analysis budget the live
         * path is already tight on (wpass-pl7.3) to re-examine an image about to be replaced.
         *
         * Its budget is inert and cannot bound that path: a one-rung ladder has no second rung
         * to skip, and the first always runs. Bounding a live frame's decode is the caller's job.
         */
        public val SINGLE_ATTEMPT: DecodeLadder = DecodeLadder(listOf(NO_CAP), 3.seconds)
    }
}

/** The pixel size of one rung. */
internal data class RungSize(val width: Int, val height: Int) {
    /** Long, not Int: the largest rung a 50 MP source can produce still overflows nothing here. */
    val area: Long get() = width.toLong() * height
}

/**
 * The distinct sizes [ladder] will actually decode a [sourceWidth] x [sourceHeight] image at, in
 * order. A cap at or above the source's longest side yields the source's own size, so caps that
 * collapse onto each other (and onto the source) are decoded once, not once per rung. Pure and
 * separated from the decode so the ladder's shape — how many attempts an input costs — is
 * assertable without running ZXing over a fixture.
 */
internal fun rungSizes(
    sourceWidth: Int,
    sourceHeight: Int,
    ladder: DecodeLadder,
): List<RungSize> {
    val longest = maxOf(sourceWidth, sourceHeight)
    return ladder.rungsPx
        .map { capPx ->
            if (longest <= capPx) {
                RungSize(sourceWidth, sourceHeight)
            } else {
                RungSize(
                    width = (sourceWidth.toLong() * capPx / longest).toInt().coerceAtLeast(1),
                    height = (sourceHeight.toLong() * capPx / longest).toInt().coerceAtLeast(1),
                )
            }
        }
        .distinct()
}

/**
 * Box-filter the [srcWidth] x [srcHeight] grayscale in [luminances] down to [width] x [height].
 * Takes the already-materialised grayscale rather than a [LuminanceSource] so a multi-rung ladder
 * pays for that conversion once no matter how many rungs it runs.
 *
 * Area-averaging, not point-sampling: averaging is what suppresses the per-pixel noise that
 * defeats the binarizer at native resolution, where point-sampling would instead alias thin bars
 * away. The result is handed back as ZXing's own [GrayscaleLuminanceSource] rather than a local
 * subclass, because [LuminanceSource]'s rotate and crop support defaults to false: a hand-rolled
 * source silently costs `OneDReader` its 90-degree retry, and with it every rotated 1D symbol in
 * any image large enough for all three rungs to resample (a 12 MP phone photo, say).
 */
internal fun scaledTo(
    luminances: ByteArray,
    srcWidth: Int,
    srcHeight: Int,
    width: Int,
    height: Int,
): LuminanceSource {
    val scaled = ByteArray(width * height)
    for (y in 0 until height) {
        val top = (y.toLong() * srcHeight / height).toInt()
        val bottom = maxOf(top + 1, ((y + 1).toLong() * srcHeight / height).toInt())
        for (x in 0 until width) {
            val left = (x.toLong() * srcWidth / width).toInt()
            val right = maxOf(left + 1, ((x + 1).toLong() * srcWidth / width).toInt())
            scaled[y * width + x] = meanOf(luminances, srcWidth, left, top, right, bottom)
        }
    }
    return GrayscaleLuminanceSource(width, height, scaled)
}

/** The mean luminance of the `[left, right) x [top, bottom)` block of a [srcWidth]-wide image. */
@Suppress("LongParameterList") // A pixel block is six numbers; a holder type would only hide them.
private fun meanOf(
    luminances: ByteArray,
    srcWidth: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
): Byte {
    var total = 0
    for (y in top until bottom) {
        val rowStart = y * srcWidth
        for (x in left until right) {
            total += luminances[rowStart + x].toInt() and 0xFF
        }
    }
    return (total / ((right - left) * (bottom - top))).toByte()
}
