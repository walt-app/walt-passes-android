package `is`.walt.passes.core.internal

import `is`.walt.passes.core.ParserConfig

/**
 * Defensive ceiling check the kotlinx parser does not natively enforce. Iterates
 * source bytes once, tracking nesting depth and the byte length of in-progress JSON
 * string tokens. ASCII-byte scanning is safe over UTF-8: continuation bytes
 * (`0x80..0xBF`) cannot collide with `{`, `}`, `[`, `]`, `"`, or `\`.
 *
 * String byte-counting uses source bytes (overcount by escape-sequence shrinkage),
 * not decoded character bytes — the guard's intent is to bound JSON-bomb expansion
 * before allocation, and the overcount is conservative (never lets an over-budget
 * string through). Returns `null` on success, or the first arm that tripped. JSON
 * well-formedness is intentionally not verified here — kotlinx.serialization handles
 * that downstream — so an unbalanced bracket pair sails through here and surfaces as
 * [PassJsonFailure.InvalidJson] from the typed parse.
 */
internal fun enforceJsonLimits(
    bytes: ByteArray,
    config: ParserConfig,
): PassJsonFailure? {
    val state =
        JsonLimitTokenizer(
            maxDepth = config.maxJsonDepth,
            maxStringBytes = config.maxJsonStringBytes,
        )
    var i = 0
    while (i < bytes.size && state.failure == null) {
        state.consume(bytes[i])
        i++
    }
    return state.failure
}

private class JsonLimitTokenizer(
    private val maxDepth: Int,
    private val maxStringBytes: Int,
) {
    var failure: PassJsonFailure? = null
        private set

    private var depth = 0
    private var inString = false
    private var stringByteCount = 0
    private var escape = false

    fun consume(b: Byte) {
        if (inString) consumeInString(b) else consumeOutsideString(b)
    }

    private fun consumeInString(b: Byte) {
        when {
            escape -> {
                escape = false
                bumpStringByte()
            }
            b == BACKSLASH -> {
                escape = true
                bumpStringByte()
            }
            b == DOUBLE_QUOTE -> inString = false
            else -> bumpStringByte()
        }
    }

    private fun consumeOutsideString(b: Byte) {
        when (b) {
            DOUBLE_QUOTE -> {
                inString = true
                stringByteCount = 0
            }
            LBRACE, LBRACKET -> {
                depth++
                if (depth > maxDepth) failure = PassJsonFailure.JsonDepthExceeded
            }
            // Clamp at zero. A payload with stray leading closers (`}}}{...`) would
            // otherwise drive depth negative, then climb back, leaving the in-flight
            // peak at `maxDepth + leadingClosers` rather than `maxDepth`. kotlinx
            // rejects the mismatched JSON downstream and the entry-size cap bounds
            // it in practice, but the invariant `0 <= depth <= maxDepth` is cheap
            // to keep and removes any reliance on those downstream bounds.
            RBRACE, RBRACKET -> if (depth > 0) depth--
        }
    }

    private fun bumpStringByte() {
        stringByteCount++
        if (stringByteCount > maxStringBytes) failure = PassJsonFailure.JsonStringTooLong
    }
}

private const val DOUBLE_QUOTE: Byte = 0x22
private const val BACKSLASH: Byte = 0x5C
private const val LBRACE: Byte = 0x7B
private const val RBRACE: Byte = 0x7D
private const val LBRACKET: Byte = 0x5B
private const val RBRACKET: Byte = 0x5D
