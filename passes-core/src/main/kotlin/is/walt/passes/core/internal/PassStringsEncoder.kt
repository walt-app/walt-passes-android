package `is`.walt.passes.core.internal

import `is`.walt.passes.core.LocalizedStrings

/**
 * Serializes one `<locale>.lproj/pass.strings` from a [LocalizedStrings]: UTF-8, no BOM,
 * one `"key" = "value";` line per entry. The inverse of [parseStrings] for the escapes
 * that lexer decodes; every other character is written verbatim, which the lexer also
 * accepts.
 *
 * Keys are sorted: [LocalizedStrings.entries] is a plain Map, so its iteration order
 * cannot be load-bearing for byte-stable output.
 */
internal fun encodeStrings(strings: LocalizedStrings): ByteArray =
    strings.entries.entries
        .sortedBy { it.key }
        .joinToString(separator = "") { (key, value) -> "\"${escapeStrings(key)}\" = \"${escapeStrings(value)}\";\n" }
        .toByteArray(Charsets.UTF_8)

private fun escapeStrings(text: String): String =
    buildString(text.length) {
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

/** The strings lexer's own per-Char overcount, so the check trips exactly where the parser does. */
internal fun conservativeUtf8Bytes(text: String): Long = text.sumOf { it.utf8Bytes().toLong() }
