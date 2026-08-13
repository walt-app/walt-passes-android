package `is`.walt.passes.barcode

import com.google.common.truth.Truth.assertThat
import com.google.zxing.RGBLuminanceSource
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.BarcodeEncoder
import `is`.walt.passes.core.EncodeResult
import `is`.walt.passes.core.ScannableFormat
import org.junit.Test

/**
 * Every roster symbology encodes through the KERNEL's [BarcodeEncoder] and decodes back
 * through the KERNEL's [decodeLuminance], to the same payload and the same [ScannableFormat].
 *
 * Distinct from [ZxingBarcodeSymbolDecoderTest], which encodes its fixtures with ZXing's own
 * `MultiFormatWriter` and therefore proves only the decoder. This suite closes the loop over
 * the encoder's pinned error-correction, compaction and quiet-zone choices — a wrong writer or
 * a wrong `BarcodeFormat` in the dispatch would still produce a plausible symbol there, and
 * only a decode that reports the format back catches it.
 *
 * Lives here rather than in `passes-core` because that module cannot see the decoder:
 * `passes-barcode-core` depends on `passes-core`, not the other way round.
 *
 * This is NOT the whole acceptance criterion. A symbol only Walt can read is not a working
 * symbol, so the third-party-scanner and on-device legs are tracked separately (wpass-sw2).
 *
 * Fixtures are synthetic. A real BCBP boarding pass carries passenger name and PNR, which
 * wpass-pl7 bars from any committed fixture.
 */
class BarcodeEncoderRoundTripTest {
    @Test
    fun everyRosterFormatRoundTripsThroughTheKernel() {
        val payloads =
            mapOf(
                ScannableFormat.Code128 to "WALT-RT-128",
                ScannableFormat.Code39 to "WALT-RT-39",
                ScannableFormat.Ean13 to "1234567890128",
                ScannableFormat.UpcA to "036000291452",
                ScannableFormat.Qr to "https://example.org/loyalty/123",
                ScannableFormat.Pdf417 to "WALT-RT-PDF417",
                ScannableFormat.Aztec to "WALT-RT-AZTEC",
            )
        // Fails closed when a roster member is added without a round trip being proven for it.
        assertThat(payloads.keys).containsExactlyElementsIn(ScannableFormat.entries)

        for ((format, payload) in payloads) {
            assertThat(decodeLuminance(render(payload, format)))
                .isEqualTo(BarcodeDecodeResult.DecodedBarcode(payload, format))
        }
    }

    @Test
    fun anExtractedBoardingPassPayloadReRendersAsAScannableAztec() {
        // The epic's user-visible outcome: a payload lifted off an imported boarding-pass
        // screenshot has to become a symbol a gate scanner reads, or extraction solved nothing.
        val payload = BCBP_SHAPED_PAYLOAD

        assertThat(decodeLuminance(render(payload, ScannableFormat.Aztec)))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode(payload, ScannableFormat.Aztec))
    }

    @Test
    fun pdf417RoundTripsABoardingPassLengthPayload() {
        assertThat(decodeLuminance(render(BCBP_SHAPED_PAYLOAD, ScannableFormat.Pdf417)))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode(BCBP_SHAPED_PAYLOAD, ScannableFormat.Pdf417))
    }

    @Test
    fun theTwoDimensionalFormatsRoundTripAtTheirValidatorCap() {
        // Encoding at the cap is pinned in passes-core; this adds that the symbol produced there
        // is still readable, which is the half a capacity check alone does not cover.
        for ((format, cap) in listOf(ScannableFormat.Pdf417 to 800, ScannableFormat.Aztec to 1_500)) {
            val payload = "WALT".repeat(cap / 4)

            assertThat(decodeLuminance(render(payload, format)))
                .isEqualTo(BarcodeDecodeResult.DecodedBarcode(payload, format))
        }
    }

    @Test
    fun nonAsciiPayloadsSurviveOnBothTwoDimensionalFormats() {
        // The charset pins, and the reason they are pins rather than defaults. At ZXing's
        // ISO-8859-1 default PDF417 throws outright while AZTEC IS THE DANGEROUS ONE: it encodes
        // happily and decodes back "café ? naïve ? ??", a wrong scan with no error at any layer.
        // Dropping either hint has to fail here, so both formats share the one payload.
        for (format in listOf(ScannableFormat.Pdf417, ScannableFormat.Aztec)) {
            assertThat(decodeLuminance(render(NON_ASCII_PAYLOAD, format)))
                .isEqualTo(BarcodeDecodeResult.DecodedBarcode(NON_ASCII_PAYLOAD, format))
        }
    }

    @Test
    fun aztecCarriesSupplementaryPlaneCharactersPdf417RefusesThem() {
        // The validator admits emoji on both formats. Aztec round-trips them; ZXing cannot put a
        // surrogate pair through PDF417 under any configuration, so the encoder names that limit
        // itself rather than letting the writer raise a message with the payload inside it.
        val payload = "https://example.org/é/👍"

        assertThat(decodeLuminance(render(payload, ScannableFormat.Aztec)))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode(payload, ScannableFormat.Aztec))
        assertThat(BarcodeEncoder.encode(payload, ScannableFormat.Pdf417))
            .isInstanceOf(EncodeResult.Failure::class.java)
    }

    /**
     * Encodes through [BarcodeEncoder] and paints the matrix the way the render layer does —
     * nearest-neighbour upscale onto white, plus a quiet zone. Both are load-bearing: at one
     * pixel per module the binarizer cannot resolve an Aztec's finder pattern, and the 1D
     * writers emit a one-module-tall strip that needs vertical extent to be locatable.
     */
    private fun render(
        payload: String,
        format: ScannableFormat,
        scale: Int = 4,
    ): RGBLuminanceSource {
        val matrix = (BarcodeEncoder.encode(payload, format) as EncodeResult.Success).matrix
        val barHeight = if (matrix.height == 1) MIN_LINEAR_BAR_MODULES else matrix.height
        val quiet = QUIET_ZONE_MODULES * scale
        val width = matrix.width * scale + 2 * quiet
        val height = barHeight * scale + 2 * quiet
        val pixels = IntArray(width * height) { WHITE }
        for (y in 0 until barHeight) {
            for (x in 0 until matrix.width) {
                if (!matrix.isSet(x, y.coerceAtMost(matrix.height - 1))) continue
                paintModule(pixels, width, quiet + x * scale, quiet + y * scale, scale)
            }
        }
        return RGBLuminanceSource(width, height, pixels)
    }

    private fun paintModule(
        pixels: IntArray,
        rowStride: Int,
        left: Int,
        top: Int,
        scale: Int,
    ) {
        for (dy in 0 until scale) {
            val row = (top + dy) * rowStride
            for (dx in 0 until scale) {
                pixels[row + left + dx] = BLACK
            }
        }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val QUIET_ZONE_MODULES = 8

        /** Stand-in vertical extent for the 1D writers, whose matrices are one module tall. */
        const val MIN_LINEAR_BAR_MODULES = 40

        /** Spans Latin-1 (é), BMP punctuation (—) and CJK, the three widths UTF-8 encodes. */
        const val NON_ASCII_PAYLOAD = "café — naïve — 東京"

        /** Synthetic, of IATA BCBP shape and length. Never a decoded boarding pass — those are PII. */
        const val BCBP_SHAPED_PAYLOAD =
            "M1TEST/PASSENGER      EABC123 CPHLHRSK 0501 227M014A0058 147>5180      " +
                "B1A              2A05512345678901 0                          "
    }
}
