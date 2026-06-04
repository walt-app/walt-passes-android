package `is`.walt.passes.barcode.android

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
 *  - the symbology allowlist holds — a format OUTSIDE the roster (PDF417) is not decoded;
 *  - a clean image with no symbol is the honest [BarcodeDecodeResult.NoBarcodeFound].
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
    fun formatOutsideRosterIsNotDecoded() {
        // PDF417 is intentionally absent from the v1 roster. With POSSIBLE_FORMATS pinned to
        // the allowlist, the reader never tries a PDF417 decoder, so a genuine PDF417 symbol
        // reads as no locatable barcode — proving the allowlist, not blind decode-everything.
        val source = encode("BOARDING-PASS-PAYLOAD", BarcodeFormat.PDF_417, 600, 300)

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
