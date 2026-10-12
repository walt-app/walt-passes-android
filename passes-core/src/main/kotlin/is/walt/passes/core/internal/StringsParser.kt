package `is`.walt.passes.core.internal

import `is`.walt.passes.core.LocalizedStrings
import `is`.walt.passes.core.ParserConfig
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Parses a single Apple .strings localization payload (the PKPASS per-locale
 * `<locale>.lproj/pass.strings`) into a [LocalizedStrings]. Pure function: no I/O,
 * iteration order matches source order via [LinkedHashMap], duplicate keys are
 * resolved last-write-wins (matching Apple's parser).
 *
 * **Grammar.** The old-style property-list strings grammar Apple's own parser reads
 * (CoreFoundation `CFOldStylePList.c`), not just the `"key" = "value";` subset Xcode
 * writes. Accepted since #252: `'`-quoted tokens, unquoted tokens drawn from
 * `[A-Za-z0-9_$/:.-]`, the `"key";` shorthand (value = key), an optional enclosing
 * `{ ... }`, octal escapes in the ASCII range, and `\a \b \f \v \' \<newline>`.
 * Still rejected: unknown escapes (`\x`), octal above `\177` (Apple maps those through
 * the NeXTSTEP Latin table, which this parser does not carry), lone surrogates,
 * unterminated strings and comments, values that are not strings, and trailing junk.
 *
 * **Defense-in-depth ordering.** The function runs three layers, in order, and the
 * earliest-firing arm wins:
 *
 *  1. Charset sniff and strict decode — a UTF-32 / UTF-16 / UTF-8 BOM signs the file;
 *     without one, a NUL-byte pattern identifies BOM-less UTF-16 (Apple sniffs it
 *     too), and UTF-8 is the default. The decoder REPORTs malformed input and
 *     unmappable characters, so undecodable bytes fail as
 *     [StringsFailure.InvalidEncoding] rather than producing U+FFFD replacement
 *     characters and silently mis-rendering on screen. There is deliberately no
 *     single-byte fallback: no legacy charset can be detected reliably.
 *  2. Hand-rolled lexer / parser ([StringsLexer]) — single-pass character walker with
 *     explicit state for in-string and in-comment tokens. Regex on this format is
 *     brittle: Apple's `\Uxxxx` escape is 4 hex digits (not a 21-bit codepoint),
 *     supplementary-plane codepoints arrive as paired `\Uxxxx\Uxxxx` surrogates,
 *     block comments do not nest, and a backslash before EOL yields the line break
 *     itself rather than a line continuation.
 *  3. Per-value byte cap — [ParserConfig.maxJsonStringBytes] applies to the decoded
 *     value of each entry (UTF-8 byte equivalent, summed as a conservative upper
 *     bound on a per-Char basis so an oversized value is rejected mid-read rather
 *     than after full materialization). A shorthand entry's key becomes its value, so
 *     the cap is applied to it there. Total file size is already bounded by
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
        decodeStringsBytes(bytes)
            ?: return StringsResult.Failed(StringsFailure.InvalidEncoding)
    return try {
        StringsResult.Ok(StringsLexer(text, config.maxJsonStringBytes).parse())
    } catch (e: StringsParseException) {
        StringsResult.Failed(e.failure)
    }
}

/**
 * Picks the charset from a BOM, else from the BOM-less UTF-16 sniff, else UTF-8, and
 * decodes strictly; a sniffed UTF-16 that fails to decode falls back to UTF-8. Returns
 * `null` if decoding fails, surfaced as [StringsFailure.InvalidEncoding].
 *
 * The BOM is stripped before decoding: Java's explicit-endian charsets do not treat it
 * as a marker and would surface a spurious leading U+FEFF.
 */
private fun decodeStringsBytes(bytes: ByteArray): String? {
    val bom = BOMS.firstOrNull { (prefix, _) -> hasPrefix(bytes, prefix) }
    return if (bom != null) {
        decodeStrict(bytes, bom.second, bom.first.size)
    } else {
        sniffBomlessUtf16(bytes)?.let { decodeStrict(bytes, it, 0) }
            ?: decodeStrict(bytes, StandardCharsets.UTF_8, 0)
    }
}

private fun decodeStrict(
    bytes: ByteArray,
    charset: Charset,
    skip: Int,
): String? {
    val decoder =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    return runCatching {
        decoder.decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString()
    }.getOrNull()
}

/**
 * ASCII-heavy UTF-16 puts a NUL in every other byte. Requiring that on at least half the
 * sampled units, and none at the other parity, keeps UTF-8 with a stray U+0000 as UTF-8.
 */
private fun sniffBomlessUtf16(bytes: ByteArray): Charset? {
    if (bytes.size < 2 || bytes.size % 2 != 0) return null
    val sample = minOf(bytes.size, UTF16_SNIFF_BYTES)
    var evenNuls = 0
    var oddNuls = 0
    for (i in 0 until sample) {
        when {
            bytes[i] != ZERO_BYTE -> Unit
            i % 2 == 0 -> evenNuls++
            else -> oddNuls++
        }
    }
    val units = sample / 2
    return when {
        evenNuls == 0 && oddNuls * 2 >= units -> StandardCharsets.UTF_16LE
        oddNuls == 0 && evenNuls * 2 >= units -> StandardCharsets.UTF_16BE
        else -> null
    }
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
internal fun Char.utf8Bytes(): Int =
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
        skipWhitespaceAndComments()
        val braced = peek() == '{'
        if (braced) pos++
        while (true) {
            skipWhitespaceAndComments()
            if (pos >= text.length || braced && peek() == '}') break
            val key = readToken(maxBytes = Int.MAX_VALUE)
            skipWhitespaceAndComments()
            val value =
                when (peek()) {
                    ';' -> key.also { checkValueCap(it, maxValueBytes) }
                    '=' -> {
                        pos++
                        readToken(maxBytes = maxValueBytes)
                    }
                    else -> stringsFail(StringsFailure.BadStructure)
                }
            consumeAfterWhitespace(';')
            map[key] = value
        }
        if (braced) {
            consumeAfterWhitespace('}')
            skipWhitespaceAndComments()
            if (pos < text.length) stringsFail(StringsFailure.BadStructure)
        }
        return LocalizedStrings(map)
    }

    private fun peek(): Char? = text.getOrNull(pos)

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
        while (pos < text.length && text[pos] !in LINE_TERMINATORS) pos++
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
        if (!found) stringsFail(StringsFailure.UnterminatedComment)
    }

    private fun readToken(maxBytes: Int): String {
        skipWhitespaceAndComments()
        val c = peek()
        return when {
            c == '"' || c == '\'' -> readQuotedString(c, maxBytes)
            c != null && c.isUnquotedTokenChar() -> readUnquotedString(maxBytes)
            else -> stringsFail(StringsFailure.BadStructure)
        }
    }

    // Apple's unquoted set includes '/', so "//" inside a token is literal, not a comment.
    private fun readUnquotedString(maxBytes: Int): String {
        val start = pos
        while (pos < text.length && text[pos].isUnquotedTokenChar()) pos++
        if (pos - start > maxBytes) stringsFail(StringsFailure.ValueTooLong)
        return text.substring(start, pos)
    }

    private fun readQuotedString(
        quote: Char,
        maxBytes: Int,
    ): String {
        pos++
        val sb = StringBuilder()
        var byteCount = 0
        while (pos < text.length) {
            val c = text[pos]
            when (c) {
                quote -> {
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
        stringsFail(StringsFailure.UnterminatedString)
    }

    private fun consumeAfterWhitespace(expected: Char) {
        skipWhitespaceAndComments()
        if (pos >= text.length || text[pos] != expected) stringsFail(StringsFailure.BadStructure)
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
        val c = peek() ?: stringsFail(StringsFailure.BadEscape)
        val simple = SIMPLE_ESCAPES[c]
        return when {
            simple != null -> {
                pos++
                simple.toString()
            }
            c in '0'..'7' -> readOctalEscape()
            c == 'U' -> readUnicodeEscape()
            else -> stringsFail(StringsFailure.BadEscape)
        }
    }

    /** One to three octal digits, as Apple reads them; only the ASCII range is accepted. */
    private fun readOctalEscape(): String {
        var value = 0
        var digits = 0
        while (digits < MAX_OCTAL_DIGITS && pos < text.length && text[pos] in '0'..'7') {
            value = value * OCTAL_RADIX + (text[pos] - '0')
            pos++
            digits++
        }
        if (value > MAX_ASCII) stringsFail(StringsFailure.BadEscape)
        return value.toChar().toString()
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
        if (first.isLowSurrogate()) stringsFail(StringsFailure.BadEscape)
        if (!first.isHighSurrogate()) return first.toString()
        val partnerOk =
            pos + SURROGATE_PARTNER_PREFIX_LEN <= text.length &&
                text[pos] == '\\' &&
                text[pos + 1] == 'U'
        if (!partnerOk) stringsFail(StringsFailure.BadEscape)
        pos += SURROGATE_PARTNER_PREFIX_LEN
        val low = readUnicodeCodeUnit()
        if (!low.isLowSurrogate()) stringsFail(StringsFailure.BadEscape)
        return "$first$low"
    }

    private fun readUnicodeCodeUnit(): Char {
        if (pos + UNICODE_ESCAPE_HEX_DIGITS > text.length) stringsFail(StringsFailure.BadEscape)
        val hex = text.substring(pos, pos + UNICODE_ESCAPE_HEX_DIGITS)
        val code = hex.toIntOrNull(HEX_RADIX) ?: stringsFail(StringsFailure.BadEscape)
        pos += UNICODE_ESCAPE_HEX_DIGITS
        return code.toChar()
    }
}

private fun stringsFail(failure: StringsFailure): Nothing = throw StringsParseException(failure)

private fun bumpByteCount(
    current: Int,
    delta: Int,
    max: Int,
): Int {
    val next = current + delta
    if (next > max) stringsFail(StringsFailure.ValueTooLong)
    return next
}

private fun checkValueCap(
    value: String,
    max: Int,
) {
    value.fold(0) { acc, c -> bumpByteCount(acc, c.utf8Bytes(), max) }
}

private fun Char.isUnquotedTokenChar(): Boolean = isAsciiLetterOrDigit() || this in UNQUOTED_PUNCTUATION

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

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
private const val OCTAL_RADIX = 8
private const val MAX_OCTAL_DIGITS = 3
private const val MAX_ASCII = 0x7F
private const val UNICODE_ESCAPE_HEX_DIGITS = 4
private const val SURROGATE_PARTNER_PREFIX_LEN = 2
private const val UTF8_TWO_BYTE_THRESHOLD = 0x80
private const val UTF8_THREE_BYTE_THRESHOLD = 0x800
private const val UTF16_SNIFF_BYTES = 64
private const val ZERO_BYTE: Byte = 0
private const val UNQUOTED_PUNCTUATION = "_$/:.-"
private const val LINE_TERMINATORS = "\n\r  "

// A backslash before CR or LF yields that character, as in Apple's parser.
private val SIMPLE_ESCAPES: Map<Char, Char> =
    mapOf(
        '\\' to '\\',
        '"' to '"',
        '\'' to '\'',
        'n' to '\n',
        'r' to '\r',
        't' to '\t',
        'a' to '\u0007',
        'b' to '\b',
        'f' to '\u000C',
        'v' to '\u000B',
        '\n' to '\n',
        '\r' to '\r',
    )

private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private val BOM_UTF16BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
private val BOM_UTF16LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
private val BOM_UTF32BE = byteArrayOf(0, 0, 0xFE.toByte(), 0xFF.toByte())
private val BOM_UTF32LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0)

// UTF-32LE before UTF-16LE: their BOMs share a two-byte prefix.
private val BOMS: List<Pair<ByteArray, Charset>> =
    listOf(
        BOM_UTF32BE to Charset.forName("UTF-32BE"),
        BOM_UTF32LE to Charset.forName("UTF-32LE"),
        BOM_UTF8 to StandardCharsets.UTF_8,
        BOM_UTF16BE to StandardCharsets.UTF_16BE,
        BOM_UTF16LE to StandardCharsets.UTF_16LE,
    )
