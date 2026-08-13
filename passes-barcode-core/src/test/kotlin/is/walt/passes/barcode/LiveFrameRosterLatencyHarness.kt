package `is`.walt.passes.barcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import `is`.walt.passes.core.BarcodeDecodeResult
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random

/**
 * The wpass-pl7.3 measurement harness: what one live-camera frame costs per symbology roster.
 * It ASSERTS NOTHING and prints a table — timings are not a CI gate, so it is skipped unless
 * asked for:
 *
 *     ./gradlew :passes-barcode-core:test --tests '*LiveFrameRosterLatencyHarness*' \
 *         -Dwalt.latencyHarness=1 --rerun-tasks -i
 *
 * It replicates `decodeOnce` (HybridBinarizer + a fresh MultiFormatReader per frame) rather than
 * calling it, so the pre-wpass-pl7.1 five-format roster and the current seven-format one can be
 * A/B'd inside ONE warm process — a cross-commit comparison cannot separate a roster delta from
 * JIT state. The real [decodeYPlane] is measured alongside and printed as `production` rows, for
 * comparison against `current-7`; that replication is this harness's one load-bearing assumption.
 *
 * ### The wpass-pl7.3 result, and why the live path still carries the full roster
 * Adding Aztec + PDF417 costs ~4.8ms per 640x480 frame. Where that lands is uneven, because ZXing
 * runs its readers QR → Aztec → PDF417 → 1D (TRY_HARDER appends the 1D reader LAST):
 *  - **QR pays nothing** (~1.4ms either way) — it exits before reaching the added readers.
 *  - **1D pays all of it** — a loyalty card queues behind three failed 2D attempts, 1.3 → 4.4ms.
 *  - **PDF417 is the dearest 2D hit** (2.2ms, against Aztec's 1.5ms), being last of the 2D readers.
 *
 * That delta was judged small enough in absolute terms to accept rather than narrow the live
 * roster. But the delta is not what bounds this path — the NO-SYMBOL frame is, being every frame
 * before lock-on, and it runs ~16ms at 640x480 and ~30ms at 1280x720 on a host JVM. Against a
 * 33ms frame interval at 30fps, that is the number to look at before widening the roster again,
 * and it says the analyzer RESOLUTION is the live budget's real lever.
 *
 * Host-JVM figures move with the machine; what survives re-measurement is the shape, not the
 * millisecond. On-device confirmation is wpass-hzh.
 */
class LiveFrameRosterLatencyHarness {
    @Test
    fun measure() {
        assumeTrue(System.getProperty(HARNESS_PROPERTY) != null)
        val rosters =
            listOf(
                Variant("baseline-5", BASELINE_FORMATS, tryHarder = true),
                Variant("current-7", CURRENT_FORMATS, tryHarder = true),
                // Mitigation A: TRY_HARDER off. ZXing appends the 1D reader LAST when it is set, so
                // dropping it should move 1D back in front of the 2D sweep.
                Variant("current-7-noTH", CURRENT_FORMATS, tryHarder = false),
                // Mitigation B: alternate two half-rosters across frames. A live frame's retry is
                // the next frame, so each frame pays for half the roster.
                // Identical to baseline-5 by construction; run last, so the two rows double as a
                // run-to-run control on how much variant order and JIT drift move a number.
                Variant("split-1d+qr", BASELINE_FORMATS, tryHarder = true),
                Variant("split-2d", listOf(BarcodeFormat.AZTEC, BarcodeFormat.PDF_417), tryHarder = true),
            )
        val geometries = listOf(640 to 480, 1280 to 720)

        println("scene            geom      roster          median_ms  p95_ms   hit")
        for ((w, h) in geometries) {
            for (scene in scenes(w, h)) {
                for ((name, formats, tryHarder) in rosters) {
                    // TRY_HARDER must be ABSENT to be off: ZXing tests containsKey, NOT the value,
                    // so mapping it to `false` still enables it.
                    val hints =
                        buildMap<DecodeHintType, Any> {
                            put(DecodeHintType.POSSIBLE_FORMATS, formats)
                            if (tryHarder) put(DecodeHintType.TRY_HARDER, true)
                        }
                    val (samples, last) = timed { decodeFrame(scene.plane, w, h, hints) }
                    report(scene.name, w, h, name, samples, hit = last != null)
                }

                // The real entry point on the current roster, printed as a row so confirming the
                // replication is a glance down the column rather than a hand-match across formats.
                val (samples, last) = timed { decodeYPlane(scene.plane, w, h, rowStride = w) }
                report(scene.name, w, h, "production", samples, last is BarcodeDecodeResult.DecodedBarcode)
            }
        }
    }

    /**
     * Sorted per-call durations of [RUNS] timed invocations of [decode], after [WARMUP] untimed
     * ones, paired with its last result. Returning the value rather than discarding it both saves
     * the caller a second decode to learn whether the frame hit, and sinks the result so no part
     * of the measured work can be optimised away as dead.
     */
    private fun <T> timed(decode: () -> T): Pair<List<Long>, T> {
        var last = decode()
        repeat(WARMUP - 1) { last = decode() }
        val samples =
            (0 until RUNS)
                .map {
                    val started = System.nanoTime()
                    last = decode()
                    System.nanoTime() - started
                }.sorted()
        return samples to last
    }

    @Suppress("LongParameterList") // A table row is its columns; a holder type would only hide them.
    private fun report(
        scene: String,
        width: Int,
        height: Int,
        roster: String,
        samples: List<Long>,
        hit: Boolean,
    ) {
        println(
            "%-16s %-9s %-15s %8.2f %8.2f   %s".format(
                scene,
                "${width}x$height",
                roster,
                samples[samples.size / 2] / 1_000_000.0,
                samples[samples.size * 95 / 100] / 1_000_000.0,
                if (hit) "yes" else "no",
            ),
        )
    }

    private fun decodeFrame(
        plane: ByteArray,
        width: Int,
        height: Int,
        hints: Map<DecodeHintType, Any>,
    ): Result? {
        val source = PlanarYUVLuminanceSource(plane, width, height, 0, 0, width, height, false)
        val binary = BinaryBitmap(HybridBinarizer(source))
        return try {
            MultiFormatReader().decode(binary, hints)
        } catch (_: ReaderException) {
            null
        }
    }

    private data class Variant(
        val name: String,
        val formats: List<BarcodeFormat>,
        val tryHarder: Boolean,
    )

    private class Scene(val name: String, val plane: ByteArray)

    /**
     * Frames a live analyzer actually sees. `empty` is the dominant one — every frame before
     * lock-on — and is textured rather than blank so the binarizer and locators do real work.
     */
    private fun scenes(
        width: Int,
        height: Int,
    ): List<Scene> =
        listOf(
            Scene("empty-scene", noiseFrame(width, height)),
            Scene("qr", frameWith(BarcodeFormat.QR_CODE, "WALT-LIVE-QR-PAYLOAD", width, height)),
            // 1D symbols are wide and short: give the writer an explicit aspect, or it returns a
            // one-pixel-tall matrix that no row-sampling reader can find.
            Scene(
                "code128",
                frameWith(
                    BarcodeFormat.CODE_128,
                    "LOYALTY-ABC-123456",
                    width,
                    height,
                    requestedSize = width * 70 / 100 to height * 35 / 100,
                ),
            ),
            Scene("aztec", frameWith(BarcodeFormat.AZTEC, BOARDING_PASS, width, height)),
            // The dearest 2D hit in the roster: PDF417 is LAST of the 2D readers, so it pays a
            // failed QR and a failed Aztec first. Stacked like a 1D symbol, so it needs an aspect.
            // Read the 640x480 row only: above that size the detector stops locating this symbol
            // against the fixture's per-pixel noise (measured, uninvestigated — a property of the
            // synthetic texture, and 640x480 is what the live path runs at).
            Scene(
                "pdf417",
                frameWith(
                    BarcodeFormat.PDF_417,
                    BOARDING_PASS,
                    width,
                    height,
                    requestedSize = width * 70 / 100 to height * 35 / 100,
                ),
            ),
        )

    /** A textured, deterministic stand-in for a real scene: soft gradient plus per-pixel noise. */
    private fun noiseFrame(
        width: Int,
        height: Int,
    ): ByteArray {
        val random = Random(SEED)
        val plane = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val gradient = 60 + 140 * x / width
                val noise = random.nextInt(48) - 24
                plane[y * width + x] = (gradient + noise).coerceIn(0, 255).toByte()
            }
        }
        return plane
    }

    /** The same textured scene with a symbol composited into the middle ~55% of the frame. */
    private fun frameWith(
        format: BarcodeFormat,
        content: String,
        width: Int,
        height: Int,
        requestedSize: Pair<Int, Int>? = null,
    ): ByteArray {
        val plane = noiseFrame(width, height)
        val matrix =
            MultiFormatWriter().encode(
                content,
                format,
                requestedSize?.first ?: 0,
                requestedSize?.second ?: 0,
                mapOf(EncodeHintType.MARGIN to 1),
            )
        val scale =
            if (requestedSize != null) {
                1
            } else {
                maxOf(1, minOf(width, height) * 55 / 100 / maxOf(matrix.width, matrix.height))
            }
        val drawW = matrix.width * scale
        val drawH = matrix.height * scale
        // A symbol wider than the frame would centre to a negative origin and index out of the
        // plane; say so, rather than surfacing it as an ArrayIndexOutOfBoundsException.
        require(drawW <= width && drawH <= height) {
            "$format at ${drawW}x$drawH does not fit a ${width}x$height frame."
        }
        val left = (width - drawW) / 2
        val top = (height - drawH) / 2

        // A white quiet zone around the symbol, as the card or screen it sits on would provide;
        // without one the noise runs straight into the modules.
        val quietZone = minOf(width, height) / 12
        for (y in (top - quietZone).coerceAtLeast(0) until (top + drawH + quietZone).coerceAtMost(height)) {
            for (x in (left - quietZone).coerceAtLeast(0) until (left + drawW + quietZone).coerceAtMost(width)) {
                plane[y * width + x] = WHITE
            }
        }
        for (y in 0 until drawH) {
            for (x in 0 until drawW) {
                plane[(top + y) * width + left + x] = if (matrix.get(x / scale, y / scale)) BLACK else WHITE
            }
        }
        return plane
    }

    private companion object {
        const val HARNESS_PROPERTY = "walt.latencyHarness"
        const val BLACK = 0x10.toByte()
        const val WHITE = 0xF0.toByte()
        const val WARMUP = 30
        const val RUNS = 120
        const val SEED = 20260813L

        /**
         * BCBP-SHAPED, INVENTED. A real boarding-pass payload carries a passenger name and PNR, so
         * none is committed here (wpass-pl7 constraint 7); this only needs the right length and
         * character mix to size the symbol realistically.
         */
        const val BOARDING_PASS = "M1TEST/PASSENGER  EABC123 CPHLHRSK 0123 456Y028A0001 100"

        val BASELINE_FORMATS =
            listOf(
                BarcodeFormat.CODE_128,
                BarcodeFormat.EAN_13,
                BarcodeFormat.UPC_A,
                BarcodeFormat.CODE_39,
                BarcodeFormat.QR_CODE,
            )
        val CURRENT_FORMATS = BASELINE_FORMATS + listOf(BarcodeFormat.PDF_417, BarcodeFormat.AZTEC)
    }
}
