package `is`.walt.passes.ui.internal

import com.google.zxing.BarcodeFormat as ZxingFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.CharacterSetECI
import java.nio.charset.Charset

/**
 * Encodes an issuer barcode so the symbol carries [message] in the pass's declared
 * [messageEncoding]. Returns null, never a silently altered payload, when the charset is
 * unknown, cannot represent [message] or be announced to a scanner, or ZXing refuses the content.
 */
internal fun encodePassBarcodeMatrix(
    message: String,
    messageEncoding: String,
    format: ZxingFormat,
    widthPx: Int,
    heightPx: Int,
): BitMatrix? {
    val charset = runCatching { Charset.forName(messageEncoding) }.getOrNull() ?: return null
    val hint = charsetHint(message, charset, format)
    val encodable = runCatching { charset.newEncoder().canEncode(message) }.getOrDefault(false)
    // Without an ECI a scanner cannot know the charset, so the symbol would decode to other text.
    val announceable = hint == null || CharacterSetECI.getCharacterSetECI(charset) != null
    val hints = hint?.let { mapOf(EncodeHintType.CHARACTER_SET to it) }
    // ZXing writers also throw unchecked exceptions on edge inputs; none may reach composition.
    return if (encodable && announceable) {
        runCatching { MultiFormatWriter().encode(message, format, widthPx, heightPx, hints) }.getOrNull()
    } else {
        null
    }
}

/**
 * ZXing's writers assume ISO-8859-1 and a CHARACTER_SET hint adds an ECI header. The hint
 * is set only when the declared bytes differ from that default, so Latin-1 symbols are unchanged.
 */
private fun charsetHint(
    message: String,
    charset: Charset,
    format: ZxingFormat,
): String? {
    val sameAsDefault =
        Charsets.ISO_8859_1.newEncoder().canEncode(message) &&
            message.toByteArray(charset).contentEquals(message.toByteArray(Charsets.ISO_8859_1))
    // Code 128 encodes characters, not charset bytes, and takes no charset hint.
    val unhinted = sameAsDefault || format == ZxingFormat.CODE_128
    return if (unhinted) null else charset.name()
}
