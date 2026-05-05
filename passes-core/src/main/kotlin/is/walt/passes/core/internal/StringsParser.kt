package `is`.walt.passes.core.internal

import `is`.walt.passes.core.LocalizedStrings
import `is`.walt.passes.core.ParserConfig
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Parses a single Apple .strings localization payload (the PKPASS per-locale
 * `<locale>.lproj/pass.strings`) into a [LocalizedStrings]. Pure function: no I/O,
 * iteration order matches source order via [LinkedHashMap], duplicate keys are
 * resolved last-write-wins (matching Apple's parser).
 *
 * **Defense-in-depth ordering.** The function runs three layers, in order, and the
 * earliest-firing arm wins:
 *
 *  1. Charset BOM-sniff and strict decode — UTF-16 LE/BE or UTF-8 BOM signs the file;
 *     otherwise UTF-8 is the documented default. The decoder is configured with
 *     [CodingErrorAction.REPORT] for both malformed input and unmappable characters,
 *     so a non-UTF-8 byte sequence with no BOM fails outright as
 *     [StringsFailure.InvalidEncoding] rather than producing U+FFFD replacement
 *     characters and silently mis-rendering on screen.
 *  2. Hand-rolled lexer / parser ([StringsLexer]) — single-pass character walker with
 *     explicit state for in-string and in-comment tokens. Regex on this format is
 *     brittle: Apple's `\Uxxxx` escape is 4 hex digits (not a 21-bit codepoint),
 *     supplementary-plane codepoints arrive as paired `\Uxxxx\Uxxxx` surrogates,
 *     block comments do not nest, and a backslash immediately before EOL is *not* a
 *     line continuation. The walker fits cleanly into ~150 lines and keeps each rule
 *     local to its branch.
 *  3. Per-value byte cap — [ParserConfig.maxJsonStringBytes] applies to the decoded
 *     value of each entry (UTF-8 byte equivalent, summed as a conservative upper
 *     bound on a per-Char basis so an oversized value is rejected mid-read rather
 *     than after full materialization). Total file size is already bounded by
 *     [ParserConfig.maxEntryBytes] upstream.
 *
 * The cap is applied to values only, not keys. Keys in real .strings files routinely
 * exceed any sensible value cap (they are often dotted human-readable identifiers);
 * the cap exists to bound memory expansion on the rendered surface, not key length.
 *
 * Surrogate handling. Supplementary-plane codepoints (emoji etc.) appear in the
 * source as `\UD83D\UDE00`-style pairs. The lexer accepts a high surrogate followed
 * immediately by `\U<low>` and emits both halves verbatim — the resulting Kotlin
 * String is well-formed UTF-16. Lone surrogates are rejected as
 * [StringsFailure.BadEscape]; the strict charset decoder blocks malformed UTF-16
 * from raw bytes, and rejecting unpaired `\Uxxxx` keeps the same posture across the
 * escape boundary.
 */
internal fun parseStrings(
    bytes: ByteArray,
    config: ParserConfig,
): StringsResult {
    val text =
        decodeWithBomSniff(bytes)
            ?: return StringsResult.Failed(StringsFailure.InvalidEncoding)
    return try {
        StringsResult.Ok(StringsLexer(text, config.maxJsonStringBytes).parse())
    } catch (e: StringsParseException) {
        StringsResult.Failed(e.failure)
    }
}

/**
 * BOM-sniffs the leading bytes for UTF-16 LE/BE / UTF-8 and decodes the rest with a
 * [java.nio.charset.CharsetDecoder] whose error policy is REPORT on both malformed
 * input and unmappable characters. Returns `null` if decoding fails — surfaced as
 * [StringsFailure.InvalidEncoding] by the caller. UTF-8 is the no-BOM default per
 * Apple's documented behavior for .strings.
 *
 * The BOM is stripped before handing bytes to the decoder. Java's `UTF_16LE` /
 * `UTF_16BE` charsets do *not* themselves treat a BOM as a marker (they are
 * explicit codings), so passing the BOM bytes through would surface the BOM as a
 * spurious U+FEFF leading character in the decoded text and break the lexer's
 * first-token assumptions.
 */
private fun decodeWithBomSniff(bytes: ByteArray): String? {
    val (charset, skip) =
        when {
            hasPrefix(bytes, BOM_UTF8) -> StandardCharsets.UTF_8 to BOM_UTF8.size
            hasPrefix(bytes, BOM_UTF16BE) -> StandardCharsets.UTF_16BE to BOM_UTF16BE.size
            hasPrefix(bytes, BOM_UTF16LE) -> StandardCharsets.UTF_16LE to BOM_UTF16LE.size
            else -> StandardCharsets.UTF_8 to 0
        }
    val decoder =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    return runCatching {
        decoder.decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString()
    }.getOrNull()
}

private fun hasPrefix(
    bytes: ByteArray,
    prefix: ByteArray,
): Boolean {
    if (bytes.size < prefix.size) return false
    return prefix.indices.all { bytes[it] == prefix[it] }
}

/**
 * Conservative UTF-8 byte count for a single [Char] (BMP code unit). Surrogate halves
 * count as 3 bytes each — a real surrogate-pair codepoint encodes to 4 UTF-8 bytes,
 * so this overcounts by 2 for any supplementary-plane character. That overcount is
 * deliberate: the function is a guard against "is this string under the cap", and a
 * conservative upper bound never lets an over-budget string through. Not suitable
 * for serialization sizing.
 */
private fun Char.utf8Bytes(): Int =
    when {
        code < UTF8_TWO_BYTE_THRESHOLD -> 1
        code < UTF8_THREE_BYTE_THRESHOLD -> 2
        else -> 3
    }

private class StringsLexer(
    private val text: String,
    private val maxValueBytes: Int,
) {
    private var pos = 0

    fun parse(): LocalizedStrings {
        val map = LinkedHashMap<String, String>()
        while (true) {
            skipWhitespaceAndComments()
            if (pos >= text.length) break
            val key = readQuotedString(maxBytes = Int.MAX_VALUE)
            consumeAfterWhitespace('=')
            val value = readQuotedString(maxBytes = maxValueBytes)
            consumeAfterWhitespace(';')
            map[key] = value
        }
        return LocalizedStrings(map)
    }

    private fun fail(failure: StringsFailure): Nothing = throw StringsParseException(failure)

    private fun skipWhitespaceAndComments() {
        while (pos < text.length) {
            val c = text[pos]
            val next = if (pos + 1 < text.length) text[pos + 1] else null
            when {
                c.isWhitespace() -> pos++
                c == '/' && next == '/' -> skipLineComment()
                c == '/' && next == '*' -> skipBlockComment()
                else -> return
            }
        }
    }

    private fun skipLineComment() {
        pos += 2
        while (pos < text.length && text[pos] != '\n' && text[pos] != '\r') pos++
        if (pos < text.length) pos++
    }

    private fun skipBlockComment() {
        pos += 2
        var found = false
        while (!found && pos + 1 < text.length) {
            if (text[pos] == '*' && text[pos + 1] == '/') {
                pos += 2
                found = true
            } else {
                pos++
            }
        }
        if (!found) fail(StringsFailure.UnterminatedComment)
    }

    private fun readQuotedString(maxBytes: Int): String {
        skipWhitespaceAndComments()
        if (pos >= text.length || text[pos] != '"') fail(StringsFailure.BadStructure)
        pos++
        val sb = StringBuilder()
        var byteCount = 0
        while (pos < text.length) {
            val c = text[pos]
            when (c) {
                '"' -> {
                    pos++
                    return sb.toString()
                }
                '\\' -> {
                    val escaped = readEscape()
                    for (e in escaped) byteCount = bumpByteCount(byteCount, e.utf8Bytes(), maxBytes)
                    sb.append(escaped)
                }
                else -> {
                    pos++
                    byteCount = bumpByteCount(byteCount, c.utf8Bytes(), maxBytes)
                    sb.append(c)
                }
            }
        }
        fail(StringsFailure.UnterminatedString)
    }

    private fun bumpByteCount(
        current: Int,
        delta: Int,
        max: Int,
    ): Int {
        val next = current + delta
        if (next > max) fail(StringsFailure.ValueTooLong)
        return next
    }

    private fun consumeAfterWhitespace(expected: Char) {
        skipWhitespaceAndComments()
        if (pos >= text.length || text[pos] != expected) fail(StringsFailure.BadStructure)
        pos++
    }

    /**
     * Returns the decoded escape as a [String]: a single Char for the bare escapes,
     * or a two-Char surrogate pair for `\Uxxxx\Uxxxx`. Returning a String (not a
     * Char) is what lets [readUnicodeEscape] emit a surrogate pair atomically — the
     * caller appends and counts both halves without having to learn that surrogate
     * pairs are a thing.
     */
    private fun readEscape(): String {
        pos++
        if (pos >= text.length) fail(StringsFailure.BadEscape)
        return when (val c = text[pos]) {
            '\\', '"' -> {
                pos++
                c.toString()
            }
            'n' -> {
                pos++
                "\n"
            }
            'r' -> {
                pos++
                "\r"
            }
            't' -> {
                pos++
                "\t"
            }
            'U' -> readUnicodeEscape()
            else -> fail(StringsFailure.BadEscape)
        }
    }

    /**
     * Decodes one or two `\Uxxxx` escapes. Apple writes supplementary-plane
     * codepoints as paired `\U<high>\U<low>` UTF-16 surrogates; the BMP path stops
     * after one. Lone surrogates (high without partner, low without preceding high)
     * are [StringsFailure.BadEscape] — emitting them would produce a malformed
     * UTF-16 String that surfaces unpredictably at any downstream UTF-8 re-encoding.
     */
    private fun readUnicodeEscape(): String {
        pos++
        val first = readUnicodeCodeUnit()
        if (first.isLowSurrogate()) fail(StringsFailure.BadEscape)
        if (!first.isHighSurrogate()) return first.toString()
        val partnerOk =
            pos + SURROGATE_PARTNER_PREFIX_LEN <= text.length &&
                text[pos] == '\\' &&
                text[pos + 1] == 'U'
        if (!partnerOk) fail(StringsFailure.BadEscape)
        pos += SURROGATE_PARTNER_PREFIX_LEN
        val low = readUnicodeCodeUnit()
        if (!low.isLowSurrogate()) fail(StringsFailure.BadEscape)
        return "$first$low"
    }

    private fun readUnicodeCodeUnit(): Char {
        if (pos + UNICODE_ESCAPE_HEX_DIGITS > text.length) fail(StringsFailure.BadEscape)
        val hex = text.substring(pos, pos + UNICODE_ESCAPE_HEX_DIGITS)
        val code = hex.toIntOrNull(HEX_RADIX) ?: fail(StringsFailure.BadEscape)
        pos += UNICODE_ESCAPE_HEX_DIGITS
        return code.toChar()
    }
}

/**
 * Internal control-flow vehicle for short-circuiting deeply-nested lexer helpers.
 * Caught only at the [parseStrings] boundary; never leaks across the public API.
 *
 * The project convention favors `Result<T>` over exceptions, but that convention is
 * about API surface. Hand-rolled lexers benefit from a non-local escape because
 * threading a nullable failure through every helper would force every method to
 * return a `StringsFailure?` and every caller to check it before advancing — for a
 * parser whose helpers are 5-15 lines apiece, the bookkeeping outweighs the benefit.
 * This is a deliberate, file-local carve-out; see [PassJsonDecoder] for the
 * alternative pattern (sealed-result returns) where helpers are larger and the
 * failure can be lifted at each boundary.
 */
private class StringsParseException(val failure: StringsFailure) : RuntimeException()

private const val HEX_RADIX = 16
private const val UNICODE_ESCAPE_HEX_DIGITS = 4
private const val SURROGATE_PARTNER_PREFIX_LEN = 2
private const val UTF8_TWO_BYTE_THRESHOLD = 0x80
private const val UTF8_THREE_BYTE_THRESHOLD = 0x800

private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private val BOM_UTF16BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
private val BOM_UTF16LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
