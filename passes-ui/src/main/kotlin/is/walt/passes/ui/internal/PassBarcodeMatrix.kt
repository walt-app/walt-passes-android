package `is`.walt.passes.ui.internal

import com.google.zxing.BarcodeFormat as ZxingFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import java.nio.charset.Charset

/**
 * Encodes an issuer barcode so the symbol carries [message] in the pass's declared
 * [messageEncoding]. Returns null, never a silently altered payload, when the charset is
 * unknown, cannot represent [message], or ZXing refuses the content.
 */
internal fun encodePassBarcodeMatrix(
    message: String,
    messageEncoding: String,
    format: ZxingFormat,
    widthPx: Int,
    heightPx: Int,
): BitMatrix? {
    val charset = runCatching { Charset.forName(messageEncoding) }.getOrNull()
    val encodable = charset != null && runCatching { charset.newEncoder().canEncode(message) }.getOrDefault(false)
    if (charset == null || !encodable) return null
    return try {
        MultiFormatWriter().encode(message, format, widthPx, heightPx, charsetHints(message, charset, format))
    } catch (_: WriterException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * ZXing's writers assume ISO-8859-1 and a CHARACTER_SET hint adds an ECI header. The hint
 * is set only when the declared bytes differ from that default, so Latin-1 symbols are unchanged.
 */
private fun charsetHints(
    message: String,
    charset: Charset,
    format: ZxingFormat,
): Map<EncodeHintType, Any>? {
    val sameAsDefault =
        Charsets.ISO_8859_1.newEncoder().canEncode(message) &&
            message.toByteArray(charset).contentEquals(message.toByteArray(Charsets.ISO_8859_1))
    // Code 128 encodes characters, not charset bytes, and takes no charset hint.
    val unhinted = sameAsDefault || format == ZxingFormat.CODE_128
    return if (unhinted) null else mapOf(EncodeHintType.CHARACTER_SET to charset.name())
}
