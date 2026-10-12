package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Reader
import com.google.zxing.aztec.AztecReader
import com.google.zxing.aztec.AztecWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Test
import com.google.zxing.BarcodeFormat as ZxingFormat

/**
 * When Aztec and QR carry a UTF-8 ECI header. Matrices are compared against direct ZXing
 * encodes at the encoder's error-correction pins, so a test fails if the header appears or vanishes.
 */
class BarcodeEncoderCharsetTest {
    @Test
    fun asciiAztecAndQrCarryNoEciHeader() {
        // Lowercase keeps QR in byte mode: numeric and alphanumeric modes never carry the header.
        for (format in listOf(ScannableFormat.Aztec, ScannableFormat.Qr)) {
            for (payload in listOf("abc123", "Member 0042-xyz")) {
                val matrix = encode(payload, format)
                assertThat(matrix).isEqualTo(zxing(payload, format, utf8 = false))
                assertThat(matrix).isNotEqualTo(zxing(payload, format, utf8 = true))
            }
        }
    }

    @Test
    fun numericAztecCarriesNoEciHeader() {
        // With the header this symbol is 19 modules.
        val matrix = encode("9876543210", ScannableFormat.Aztec)
        assertThat(matrix).isEqualTo(zxing("9876543210", ScannableFormat.Aztec, utf8 = false))
        assertThat(matrix.width).isEqualTo(15)
    }

    @Test
    fun latin1AztecCarriesNoEciHeader() {
        val payload = "Grüße aus Köln"
        assertThat(encode(payload, ScannableFormat.Aztec))
            .isEqualTo(zxing(payload, ScannableFormat.Aztec, utf8 = false))
    }

    @Test
    fun nonAsciiQrKeepsUtf8() {
        // QR decoders guess the charset of unannounced bytes, so Latin-1 still needs the header.
        val payload = "Grüße aus Köln"
        assertThat(encode(payload, ScannableFormat.Qr))
            .isEqualTo(zxing(payload, ScannableFormat.Qr, utf8 = true))
    }

    @Test
    fun nonLatin1PayloadsKeepUtf8() {
        for (format in listOf(ScannableFormat.Aztec, ScannableFormat.Qr)) {
            assertThat(encode("東京", format)).isEqualTo(zxing("東京", format, utf8 = true))
        }
    }

    @Test
    fun unhintedLatin1QrDoesNotDecodeBack() {
        // The premise behind QR's ASCII rule: without a header the decoder guesses the charset.
        val unhinted = zxing("ééé", ScannableFormat.Qr, utf8 = false)
        assertThat(decode(unhinted, ScannableFormat.Qr)).isNotEqualTo("ééé")
    }

    @Test
    fun decodeRoundTripReturnsTheExactPayload() {
        // "ééé" and "½¼¾ÀÁÂ" decode as Shift_JIS from a QR symbol without an ECI header.
        val payloads =
            listOf("abc123", "WALT-CHECK-1", "C:\\cards\\~42", "Grüße aus Köln", "ééé", "½¼¾ÀÁÂ", "東京", "a👍b")
        for (format in listOf(ScannableFormat.Aztec, ScannableFormat.Qr)) {
            for (payload in payloads) {
                assertThat(decode(encode(payload, format), format)).isEqualTo(payload)
            }
        }
    }

    private fun encode(
        payload: String,
        format: ScannableFormat,
    ): BarcodeMatrix = (BarcodeEncoder.encode(payload, format) as EncodeResult.Success).matrix

    private fun zxing(
        payload: String,
        format: ScannableFormat,
        utf8: Boolean,
    ): BarcodeMatrix {
        val charset = if (utf8) mapOf(EncodeHintType.CHARACTER_SET to "UTF-8") else emptyMap()
        val bits =
            when (format) {
                ScannableFormat.Aztec ->
                    AztecWriter().encode(
                        payload,
                        ZxingFormat.AZTEC,
                        0,
                        0,
                        mapOf(EncodeHintType.ERROR_CORRECTION to AZTEC_EC_PERCENT) + charset,
                    )
                ScannableFormat.Qr ->
                    QRCodeWriter().encode(
                        payload,
                        ZxingFormat.QR_CODE,
                        0,
                        0,
                        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M) + charset,
                    )
                else -> error("not a charset-hinted format: $format")
            }
        return bits.toBarcodeMatrix()
    }

    private fun decode(
        matrix: BarcodeMatrix,
        format: ScannableFormat,
    ): String {
        val reader: Reader = if (format == ScannableFormat.Aztec) AztecReader() else QRCodeReader()
        return reader.decode(matrix.toBitmap(), mapOf(DecodeHintType.PURE_BARCODE to true)).text
    }

    private fun BitMatrix.toBarcodeMatrix(): BarcodeMatrix =
        BarcodeMatrix(width, height, BooleanArray(width * height) { this[it % width, it / width] })

    // Scaled up with a white border so the readers sample clean modules.
    private fun BarcodeMatrix.toBitmap(): BinaryBitmap {
        val w = (width + 2 * BORDER) * SCALE
        val h = (height + 2 * BORDER) * SCALE
        val pixels =
            IntArray(w * h) { i ->
                val x = i % w / SCALE - BORDER
                val y = i / w / SCALE - BORDER
                val dark = x in 0 until width && y in 0 until height && isSet(x, y)
                if (dark) BLACK else WHITE
            }
        return BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels)))
    }

    private companion object {
        const val AZTEC_EC_PERCENT = 33
        const val SCALE = 4
        const val BORDER = 8
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
