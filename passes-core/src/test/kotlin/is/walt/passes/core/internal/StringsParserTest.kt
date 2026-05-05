package `is`.walt.passes.core.internal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import `is`.walt.passes.core.MalformedReason
import `is`.walt.passes.core.ParserConfig
import `is`.walt.passes.core.ResourceLimit
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Behavior tests for [parseStrings]. Every fixture is built in-memory from string
 * literals (and a few hand-crafted byte arrays for the BOM / non-UTF-8 cases) so a
 * reviewer can read each test and see exactly which arm of [StringsResult] is being
 * exercised. No checked-in binary fixtures.
 */
class StringsParserTest {
    @Test
    fun happyPathFiveEntriesWithMixedComments() {
        val source =
            """
            // header comment, ignored
            "from" = "SFO";
            /* block comment
               spanning multiple lines */
            "to" = "JFK";
            "flight" = "AA42"; // trailing comment
            "gate" = "B23";
            "seat" = "12A";
            """.trimIndent()
        val ok = parseStrings(source.toByteArray(), ParserConfig()).asOk()
        assertThat(ok.strings.entries).containsExactly(
            "from", "SFO",
            "to", "JFK",
            "flight", "AA42",
            "gate", "B23",
            "seat", "12A",
        ).inOrder()
    }

    @Test
    fun emptyFileReturnsEmptyMap() {
        val ok = parseStrings(ByteArray(0), ParserConfig()).asOk()
        assertThat(ok.strings.entries).isEmpty()
    }

    @Test
    fun commentOnlyFileReturnsEmptyMap() {
        val source = "// only a line comment\n/* and a block */\n"
        val ok = parseStrings(source.toByteArray(), ParserConfig()).asOk()
        assertThat(ok.strings.entries).isEmpty()
    }

    @Test
    fun duplicateKeyLastWriteWins() {
        // Apple's runtime takes the last assignment; we match that to avoid a
        // double-rendered row when an author copy-pastes a key.
        val source =
            """
            "k" = "first";
            "k" = "second";
            "k" = "third";
            """.trimIndent()
        val ok = parseStrings(source.toByteArray(), ParserConfig()).asOk()
        assertThat(ok.strings.entries).containsExactly("k", "third")
    }

    @Test
    fun utf8BomIsStrippedAndContentDecoded() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val payload = "\"k\" = \"v\";".toByteArray(StandardCharsets.UTF_8)
        val ok = parseStrings(bom + payload, ParserConfig()).asOk()
        assertThat(ok.strings.entries).containsExactly("k", "v")
    }

    @Test
    fun utf16LeBomDecoded() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val payload = "\"k\" = \"v\";".toByteArray(StandardCharsets.UTF_16LE)
        val ok = parseStrings(bom + payload, ParserConfig()).asOk()
        assertThat(ok.strings.entries).containsExactly("k", "v")
    }

    @Test
    fun utf16BeBomDecoded() {
        val bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val payload = "\"k\" = \"v\";".toByteArray(StandardCharsets.UTF_16BE)
        val ok = parseStrings(bom + payload, ParserConfig()).asOk()
        assertThat(ok.strings.entries).containsExactly("k", "v")
    }

    @Test
    fun nonUtf8WithoutBomReturnsFailed() {
        // 0xC3 alone is an invalid UTF-8 lead byte (a 2-byte sequence start whose
        // continuation byte does not satisfy 10xxxxxx). The strict decoder REPORTs
        // and we surface a structural failure rather than emit U+FFFD silently.
        val bytes = byteArrayOf(0xC3.toByte(), 0x28)
        assertFailedWith(parseStrings(bytes, ParserConfig()), MalformedReason.InvalidPassJson)
    }

    @Test
    fun escapeSequencesRoundTrip() {
        // Source contains \" \n \r \t \\ in that order between letters.
        val source = "\"k\" = \"a\\\"b\\nc\\rd\\te\\\\f\";"
        val ok = parseStrings(source.toByteArray(), ParserConfig()).asOk()
        assertThat(ok.strings.entries["k"]).isEqualTo("a\"b\nc\rd\te\\f")
    }

    @Test
    fun unicodeEscapeDecodesFourHexDigits() {
        // \U00E9 -> é (U+00E9, 2-byte UTF-8); \U2603 -> ☃ (U+2603, 3-byte UTF-8).
        val source = "\"acc\" = \"caf\\U00E9\";\n\"snowman\" = \"\\U2603\";"
        val ok = parseStrings(source.toByteArray(), ParserConfig()).asOk()
        assertThat(ok.strings.entries["acc"]).isEqualTo("café")
        assertThat(ok.strings.entries["snowman"]).isEqualTo("☃")
    }

    @Test
    fun unrecognizedEscapeReturnsFailed() {
        // \x is not an Apple-supported escape; the strict reader rejects it rather
        // than silently emitting `x`, which would let an attacker smuggle bytes past
        // a downstream renderer that thinks the parser stripped escapes.
        val source = "\"k\" = \"a\\xb\";"
        assertFailedWith(parseStrings(source.toByteArray(), ParserConfig()), MalformedReason.InvalidPassJson)
    }

    @Test
    fun unterminatedQuotedStringReturnsFailed() {
        val source = "\"k\" = \"unterminated"
        assertFailedWith(parseStrings(source.toByteArray(), ParserConfig()), MalformedReason.InvalidPassJson)
    }

    @Test
    fun unterminatedBlockCommentReturnsFailed() {
        val source = "/* never closed"
        assertFailedWith(parseStrings(source.toByteArray(), ParserConfig()), MalformedReason.InvalidPassJson)
    }

    @Test
    fun missingEqualsReturnsFailed() {
        val source = "\"k\" \"v\";"
        assertFailedWith(parseStrings(source.toByteArray(), ParserConfig()), MalformedReason.InvalidPassJson)
    }

    @Test
    fun missingSemicolonReturnsFailed() {
        val source = "\"k\" = \"v\""
        assertFailedWith(parseStrings(source.toByteArray(), ParserConfig()), MalformedReason.InvalidPassJson)
    }

    @Test
    fun perValueByteCapTrip() {
        val cfg = ParserConfig(maxJsonStringBytes = 8)
        val source = "\"k\" = \"" + "x".repeat(16) + "\";"
        assertFailedWith(
            parseStrings(source.toByteArray(), cfg),
            MalformedReason.ResourceLimitExceeded(ResourceLimit.JsonStringSize),
        )
    }

    @Test
    fun perValueByteCapAcceptsAtTheLimit() {
        val cfg = ParserConfig(maxJsonStringBytes = 8)
        val source = "\"k\" = \"" + "x".repeat(8) + "\";"
        val ok = parseStrings(source.toByteArray(), cfg).asOk()
        assertThat(ok.strings.entries["k"]).isEqualTo("x".repeat(8))
    }

    @Test
    fun perValueCapDoesNotApplyToKeys() {
        // Real .strings files use long dotted identifiers as keys; the cap is for
        // value memory expansion on the rendered surface, not key length.
        val cfg = ParserConfig(maxJsonStringBytes = 4)
        val longKey = "k".repeat(64)
        val source = "\"$longKey\" = \"v\";"
        val ok = parseStrings(source.toByteArray(), cfg).asOk()
        assertThat(ok.strings.entries[longKey]).isEqualTo("v")
    }

    @Test
    fun multibyteCharacterUsesUtf8ByteCountNotCharCount() {
        // Snowman ☃ (U+2603) is 3 bytes in UTF-8 but one Char. With cap=2 the value
        // must be rejected — a naive char-count guard would let it through.
        val cfg = ParserConfig(maxJsonStringBytes = 2)
        val source = "\"k\" = \"\\U2603\";"
        assertFailedWith(
            parseStrings(source.toByteArray(), cfg),
            MalformedReason.ResourceLimitExceeded(ResourceLimit.JsonStringSize),
        )
    }

    @Test
    fun escapedQuoteInsideStringDoesNotTerminate() {
        // Regression guard: \" inside a string must continue the value, not end it.
        val source = "\"k\" = \"a\\\"b\";"
        val ok = parseStrings(source.toByteArray(), ParserConfig()).asOk()
        assertThat(ok.strings.entries["k"]).isEqualTo("a\"b")
    }

    @Test
    fun iterationOrderMatchesSourceOrder() {
        // LinkedHashMap surfaces insertion order; assert by lifting keys to a list
        // because Truth's MapSubject ordering check inspects entrySet iteration.
        val source =
            """
            "z" = "1";
            "a" = "2";
            "m" = "3";
            """.trimIndent()
        val ok = parseStrings(source.toByteArray(), ParserConfig()).asOk()
        assertThat(ok.strings.entries.keys.toList()).containsExactly("z", "a", "m").inOrder()
    }

    @Test
    fun trailingCommentAfterFinalSemicolonIsIgnored() {
        val source = "\"k\" = \"v\"; // trailing\n"
        val ok = parseStrings(source.toByteArray(), ParserConfig()).asOk()
        assertThat(ok.strings.entries).containsExactly("k", "v")
    }

    @Test
    fun bareBomOnlyFileReturnsEmptyMap() {
        // A file with only a BOM (no payload) must decode to empty text and surface
        // as Ok(empty), not InvalidPassJson — the BOM is a marker, not content.
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val ok = parseStrings(bom, ParserConfig()).asOk()
        assertThat(ok.strings.entries).isEmpty()
    }

    private fun StringsResult.asOk(): StringsResult.Ok {
        assertThat(this).isInstanceOf(StringsResult.Ok::class.java)
        return this as StringsResult.Ok
    }

    private fun assertFailedWith(
        actual: StringsResult,
        expected: MalformedReason,
    ) {
        assertThat(actual).isInstanceOf(StringsResult.Failed::class.java)
        val reason = (actual as StringsResult.Failed).reason
        assertWithMessage("expected MalformedReason=$expected, got $reason").that(reason).isEqualTo(expected)
    }
}
