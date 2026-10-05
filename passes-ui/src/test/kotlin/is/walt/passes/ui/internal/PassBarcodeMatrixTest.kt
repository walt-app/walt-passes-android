package `is`.walt.passes.ui.internal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import org.junit.Test

class PassBarcodeMatrixTest {
    @Test
    fun declaredUtf8PayloadRoundTripsInEveryTwoDimensionalFormat() {
        for (format in TWO_DIMENSIONAL) {
            val matrix = encode("Ticket-東京-123", "utf-8", format)
            assertWithMessage("$format").that(matrix).isNotNull()
            assertWithMessage("$format").that(decode(matrix!!)).isEqualTo("Ticket-東京-123")
        }
    }

    @Test
    fun declaredNonLatinLegacyCharsetRoundTrips() {
        val matrix = encode("Билет-42", "windows-1251", BarcodeFormat.QR_CODE)
        assertThat(decode(matrix!!)).isEqualTo("Билет-42")
    }

    @Test
    fun latin1PayloadIsTheSameSymbolAsAnUnhintedEncode() {
        for (format in TWO_DIMENSIONAL) {
            for (declared in listOf("iso-8859-1", "ISO-8859-1", "utf-8")) {
                val message = if (declared == "utf-8") "PLAIN-ASCII-123" else "café-123"
                val expected = MultiFormatWriter().encode(message, format, SIZE, SIZE)
                assertWithMessage("$format $declared").that(encode(message, declared, format)).isEqualTo(expected)
            }
        }
    }

    @Test
    fun payloadTheDeclaredCharsetCannotRepresentIsRefused() {
        for (format in TWO_DIMENSIONAL) {
            assertWithMessage("$format").that(encode("Ticket-東京-123", "iso-8859-1", format)).isNull()
        }
    }

    @Test
    fun unknownOrMalformedCharsetNameIsRefused() {
        assertThat(encode("abc", "no-such-charset", BarcodeFormat.QR_CODE)).isNull()
        assertThat(encode("abc", "", BarcodeFormat.QR_CODE)).isNull()
        assertThat(encode("abc", "utf 8!", BarcodeFormat.QR_CODE)).isNull()
    }

    @Test
    fun code128AsciiRoundTripsAndNonAsciiIsRefused() {
        val matrix = encodePassBarcodeMatrix("ABC-123", "utf-8", BarcodeFormat.CODE_128, 960, 288)
        assertThat(decode(matrix!!)).isEqualTo("ABC-123")
        assertThat(encodePassBarcodeMatrix("東京", "utf-8", BarcodeFormat.CODE_128, 960, 288)).isNull()
    }

    private fun encode(
        message: String,
        declared: String,
        format: BarcodeFormat,
    ): BitMatrix? = encodePassBarcodeMatrix(message, declared, format, SIZE, SIZE)

    private fun decode(matrix: BitMatrix): String {
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] = if (matrix.get(x, y)) BLACK else WHITE
            }
        }
        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    private companion object {
        const val SIZE = 720
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        val TWO_DIMENSIONAL = listOf(BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417)
    }
}
