package `is`.walt.passes.barcode

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.ScannableFormat
import org.junit.Test

/**
 * Pins the pure-JVM ZXing decode contract (wpass-zrt.4) without a device or the platform image
 * codec: [decodeLuminance] takes a `LuminanceSource`, so each roster symbology is round-tripped
 * by ENCODING it with ZXing's own [MultiFormatWriter] and decoding the result back. This is the
 * exact pixel→symbol path the on-device decode runs minus the `Bitmap` glue (which is the
 * instrumented half, wpass-zrt.5).
 *
 * What these assert:
 *  - every [ScannableFormat] in the roster decodes back to its payload + format faithfully;
 *  - the symbology allowlist holds — a format OUTSIDE the roster (DataMatrix) is not decoded;
 *  - a clean image with no symbol is the honest [BarcodeDecodeResult.NoBarcodeFound].
 *
 * Fixtures are ENCODED IN-TEST rather than committed as images. That keeps the suite
 * device-free, and it keeps real boarding-pass payloads — which carry passenger name and PNR
 * (wpass-pl7) — out of the repository: the 2D cases below use synthetic strings only.
 */
class ZxingBarcodeSymbolDecoderTest {
    @Test
    fun qrPayloadRoundTripsFaithfully() {
        val source = encode("WALT-PASS-9", BarcodeFormat.QR_CODE, 320, 320)

        assertThat(decodeLuminance(source))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode("WALT-PASS-9", ScannableFormat.Qr))
    }

    @Test
    fun code128RoundTrips() {
        val source = encode("LOYALTY-ABC-123", BarcodeFormat.CODE_128, 600, 200)

        assertThat(decodeLuminance(source))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode("LOYALTY-ABC-123", ScannableFormat.Code128))
    }

    @Test
    fun code39RoundTrips() {
        val source = encode("MEMBER39", BarcodeFormat.CODE_39, 600, 200)

        assertThat(decodeLuminance(source))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode("MEMBER39", ScannableFormat.Code39))
    }

    @Test
    fun ean13RoundTrips() {
        // A valid 13-digit EAN-13 (trailing check digit included).
        val source = encode("5901234123457", BarcodeFormat.EAN_13, 600, 200)

        assertThat(decodeLuminance(source))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode("5901234123457", ScannableFormat.Ean13))
    }

    @Test
    fun upcaRoundTrips() {
        // A valid 12-digit UPC-A.
        val source = encode("036000291452", BarcodeFormat.UPC_A, 600, 200)

        assertThat(decodeLuminance(source))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode("036000291452", ScannableFormat.UpcA))
    }

    @Test
    fun pdf417RoundTrips() {
        val source = encode("WALT-PDF417-CHECK", BarcodeFormat.PDF_417, 600, 300)

        assertThat(decodeLuminance(source))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode("WALT-PDF417-CHECK", ScannableFormat.Pdf417))
    }

    @Test
    fun aztecRoundTrips() {
        val source = encode("WALT-AZTEC-CHECK", BarcodeFormat.AZTEC, 300, 300)

        assertThat(decodeLuminance(source))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode("WALT-AZTEC-CHECK", ScannableFormat.Aztec))
    }

    @Test
    fun aztecDecodesAtBcbpPayloadLength() {
        // The wpass-pl7 repro is an Aztec carrying a ~126-character IATA BCBP string. Shape and
        // length are what matter to the decoder, so this stands in a synthetic string of the
        // same size rather than a real boarding pass (those payloads are PII).
        val payload = "M1WALT/TEST".padEnd(126, 'X')
        val source = encode(payload, BarcodeFormat.AZTEC, 400, 400)

        assertThat(decodeLuminance(source))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode(payload, ScannableFormat.Aztec))
    }

    @Test
    fun formatOutsideRosterIsNotDecoded() {
        // DataMatrix is deliberately out of the roster (wpass-pl7 scope decision). With
        // POSSIBLE_FORMATS pinned to the allowlist, the reader never tries a DataMatrix
        // decoder, so a genuine DataMatrix symbol reads as no locatable barcode — proving the
        // allowlist, not blind decode-everything.
        val source = encode("NOT-IN-ROSTER", BarcodeFormat.DATA_MATRIX, 300, 300)

        assertThat(decodeLuminance(source)).isEqualTo(BarcodeDecodeResult.NoBarcodeFound)
    }

    @Test
    fun blankImageReturnsNoBarcodeFound() {
        val white = IntArray(120 * 120) { 0xFFFFFFFF.toInt() }

        assertThat(decodeLuminance(RGBLuminanceSource(120, 120, white)))
            .isEqualTo(BarcodeDecodeResult.NoBarcodeFound)
    }

    /** Encode [content] as [format] with ZXing's writer and expose it as a [RGBLuminanceSource]. */
    private fun encode(
        content: String,
        format: BarcodeFormat,
        width: Int,
        height: Int,
    ): RGBLuminanceSource {
        val matrix = MultiFormatWriter().encode(content, format, width, height)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix.get(x, y)) BLACK else WHITE
            }
        }
        return RGBLuminanceSource(w, h, pixels)
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
