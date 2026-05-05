package `is`.walt.passes.core.internal

import `is`.walt.passes.core.LocalizedStrings
import `is`.walt.passes.core.MalformedReason
import `is`.walt.passes.core.ParserConfig
import `is`.walt.passes.core.ResourceLimit
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
 *     [MalformedReason.InvalidPassJson] rather than producing U+FFFD replacement
 *     characters and silently mis-rendering on screen.
 *  2. Hand-rolled lexer / parser ([StringsLexer]) — single-pass character walker with
 *     explicit state for in-string and in-comment tokens. Regex on this format is
 *     brittle: Apple's `\Uxxxx` escape is 4 hex digits (not a 21-bit codepoint), block
 *     comments do not nest, and a backslash immediately before EOL is *not* a line
 *     continuation. The walker fits cleanly into ~150 lines and keeps each rule local
 *     to its branch.
 *  3. Per-value byte cap — [ParserConfig.maxJsonStringBytes] applies to the decoded
 *     value of each entry (UTF-8 byte equivalent, summed as a conservative upper
 *     bound on a per-Char basis so an oversized value is rejected mid-read rather
 *     than after full materialization). The same knob the pass.json parser uses;
 *     introducing a separate `maxStringsValueBytes` would be a knob without a turner
 *     — wpass-oj8 covers any global decompressed footprint cap. Total file size is
 *     already bounded by [ParserConfig.maxEntryBytes] upstream.
 *
 * The cap is applied to values only, not keys. Keys in real .strings files routinely
 * exceed any sensible value cap (they are often dotted human-readable identifiers);
 * the cap exists to bound memory expansion on the rendered surface, not key length.
 */
internal fun parseStrings(
    bytes: ByteArray,
    config: ParserConfig,
): StringsResult {
    val text =
        decodeWithBomSniff(bytes)
            ?: return StringsResult.Failed(MalformedReason.InvalidPassJson)
    return try {
        StringsResult.Ok(StringsLexer(text, config.maxJsonStringBytes).parse())
    } catch (e: StringsParseException) {
        StringsResult.Failed(e.reason)
    }
}

/**
 * BOM-sniffs the leading bytes for UTF-16 LE/BE / UTF-8 and decodes the rest with a
 * [java.nio.charset.CharsetDecoder] whose error policy is REPORT on both malformed
 * input and unmappable characters. Returns `null` if decoding fails — surfaced as
 * [MalformedReason.InvalidPassJson] by the caller. UTF-8 is the no-BOM default per
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
    prefix: IntArray,
): Boolean {
    if (bytes.size < prefix.size) return false
    return prefix.indices.all { bytes[it].toInt() and BYTE_MASK == prefix[it] }
}

/**
 * Conservative UTF-8 byte count for a single [Char] (BMP code unit). Surrogate halves
 * count as 3 bytes each, an upper bound on the actual encoded length without paying
 * for an actual encode. Suitable for bounding "is this string under the cap"; not
 * suitable for serialization sizing.
 */
private fun Char.utf8Bytes(): Int =
    when {
        code < UTF8_TWO_BYTE_THRESHOLD -> 1
        code < UTF8_THREE_BYTE_THRESHOLD -> 2
        else -> 3
    }

/**
 * Internal control-flow vehicle for short-circuiting deeply-nested lexer helpers.
 * Caught only at the [parseStrings] boundary; never leaks across the public API.
 * Using an exception here keeps each helper a single-return function and avoids
 * threading a nullable failure state through every level.
 */
private class StringsParseException(val reason: MalformedReason) : RuntimeException()

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

    private fun fail(reason: MalformedReason): Nothing = throw StringsParseException(reason)

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
        while (pos < text.length && text[pos] != '\n') pos++
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
        if (!found) fail(MalformedReason.InvalidPassJson)
    }

    private fun readQuotedString(maxBytes: Int): String {
        skipWhitespaceAndComments()
        if (pos >= text.length || text[pos] != '"') fail(MalformedReason.InvalidPassJson)
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
                    val ch = readEscape()
                    byteCount = bumpByteCount(byteCount, ch.utf8Bytes(), maxBytes)
                    sb.append(ch)
                }
                else -> {
                    pos++
                    byteCount = bumpByteCount(byteCount, c.utf8Bytes(), maxBytes)
                    sb.append(c)
                }
            }
        }
        fail(MalformedReason.InvalidPassJson)
    }

    private fun bumpByteCount(
        current: Int,
        delta: Int,
        max: Int,
    ): Int {
        val next = current + delta
        if (next > max) fail(MalformedReason.ResourceLimitExceeded(ResourceLimit.JsonStringSize))
        return next
    }

    private fun consumeAfterWhitespace(expected: Char) {
        skipWhitespaceAndComments()
        if (pos >= text.length || text[pos] != expected) fail(MalformedReason.InvalidPassJson)
        pos++
    }

    private fun readEscape(): Char {
        pos++
        if (pos >= text.length) fail(MalformedReason.InvalidPassJson)
        return when (val c = text[pos]) {
            '\\', '"' -> {
                pos++
                c
            }
            'n' -> {
                pos++
                '\n'
            }
            'r' -> {
                pos++
                '\r'
            }
            't' -> {
                pos++
                '\t'
            }
            'U' -> readUnicodeEscape()
            else -> fail(MalformedReason.InvalidPassJson)
        }
    }

    private fun readUnicodeEscape(): Char {
        pos++
        if (pos + UNICODE_ESCAPE_HEX_DIGITS > text.length) fail(MalformedReason.InvalidPassJson)
        val hex = text.substring(pos, pos + UNICODE_ESCAPE_HEX_DIGITS)
        val code = hex.toIntOrNull(HEX_RADIX) ?: fail(MalformedReason.InvalidPassJson)
        pos += UNICODE_ESCAPE_HEX_DIGITS
        return code.toChar()
    }
}

private const val BYTE_MASK = 0xFF
private const val HEX_RADIX = 16
private const val UNICODE_ESCAPE_HEX_DIGITS = 4
private const val UTF8_TWO_BYTE_THRESHOLD = 0x80
private const val UTF8_THREE_BYTE_THRESHOLD = 0x800

private val BOM_UTF8 = intArrayOf(0xEF, 0xBB, 0xBF)
private val BOM_UTF16BE = intArrayOf(0xFE, 0xFF)
private val BOM_UTF16LE = intArrayOf(0xFF, 0xFE)
