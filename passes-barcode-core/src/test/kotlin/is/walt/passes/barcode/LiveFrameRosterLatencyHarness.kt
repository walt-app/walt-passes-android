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
 * JIT state. Fidelity is checked at the end against the real [decodeYPlane].
 *
 * Its wpass-pl7.3 result, which is why the live path still carries the full roster: the added
 * Aztec + PDF417 readers cost ~4.4ms per frame at 640x480. QR is unaffected (ZXing reaches
 * QRCodeReader before them), Code128 pays all of it (TRY_HARDER appends the 1D reader LAST), and
 * the absolute cost was judged small enough to accept rather than narrow the live roster.
 *
 * Re-running this is the cheap check when a symbology is added — WATCH OUT for one ZXing trap it
 * hides: `TRY_HARDER` is read with `containsKey`, NOT by value, so mapping it to `false` still
 * enables it. A variant that disables the hint must OMIT the key.
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
                Variant("split-1d+qr", BASELINE_FORMATS, tryHarder = true),
                Variant("split-2d", listOf(BarcodeFormat.AZTEC, BarcodeFormat.PDF_417), tryHarder = true),
            )
        val geometries = listOf(640 to 480, 1280 to 720)

        println("scene            geom      roster          median_ms  p95_ms   hit")
        for ((w, h) in geometries) {
            for (scene in scenes(w, h)) {
                for ((name, formats, tryHarder) in rosters) {
                    // TRY_HARDER must be ABSENT to be off: ZXing tests containsKey, not the value.
                    val hints =
                        buildMap<DecodeHintType, Any> {
                            put(DecodeHintType.POSSIBLE_FORMATS, formats)
                            if (tryHarder) put(DecodeHintType.TRY_HARDER, true)
                        }
                    val samples = ArrayList<Long>(RUNS)
                    var hit = false
                    repeat(WARMUP + RUNS) { i ->
                        val started = System.nanoTime()
                        val result = decodeFrame(scene.plane, w, h, hints)
                        val elapsed = System.nanoTime() - started
                        if (i >= WARMUP) samples.add(elapsed)
                        hit = result != null
                    }
                    samples.sort()
                    println(
                        "%-16s %-9s %-15s %8.2f %8.2f   %s".format(
                            scene.name,
                            "${w}x$h",
                            name,
                            samples[samples.size / 2] / 1_000_000.0,
                            samples[samples.size * 95 / 100] / 1_000_000.0,
                            if (hit) "yes" else "no",
                        ),
                    )
                }
            }
        }

        // Sanity check: the production entry point on the current roster should land on the
        // current-7 numbers above, confirming the replication is faithful.
        val (w, h) = 640 to 480
        val production =
            scenes(w, h).associate { scene ->
                repeat(WARMUP) { decodeYPlane(scene.plane, w, h, rowStride = w) }
                val samples =
                    (0 until RUNS).map {
                        val started = System.nanoTime()
                        decodeYPlane(scene.plane, w, h, rowStride = w)
                        System.nanoTime() - started
                    }.sorted()
                scene.name to samples[samples.size / 2] / 1_000_000.0
            }
        println("production decodeYPlane 640x480 medians (current roster): $production")
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
            Scene("aztec", frameWith(BarcodeFormat.AZTEC, "M1TEST/PASSENGER  EABC123 CPHLHRSK", width, height)),
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
        val left = (width - drawW) / 2
        val top = (height - drawH) / 2
        for (y in 0 until drawH) {
            for (x in 0 until drawW) {
                val black = matrix.get(x / scale, y / scale)
                plane[(top + y) * width + left + x] = if (black) 0x10.toByte() else 0xF0.toByte()
            }
        }
        return plane
    }

    private companion object {
        const val HARNESS_PROPERTY = "walt.latencyHarness"
        const val WARMUP = 30
        const val RUNS = 120
        const val SEED = 20260813L

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
