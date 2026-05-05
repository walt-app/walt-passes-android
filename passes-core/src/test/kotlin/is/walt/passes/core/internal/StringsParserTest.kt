package `is`.walt.passes.core.internal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import `is`.walt.passes.core.ParserConfig
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Behavior tests for [parseStrings]. Every fixture is built in-memory from string
 * literals (and a few hand-crafted byte arrays for the BOM / non-UTF-8 cases) so a
 * reviewer can read each test and see exactly which arm of [StringsResult] is
 * being exercised. No checked-in binary fixtures.
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
    fun nonUtf8WithoutBomReturnsInvalidEncoding() {
        // 0xC3 alone is an invalid UTF-8 lead byte (a 2-byte sequence start whose
        // continuation byte does not satisfy 10xxxxxx). The strict decoder REPORTs
        // and we surface a structural failure rather than emit U+FFFD silently.
        val bytes = byteArrayOf(0xC3.toByte(), 0x28)
        assertFailedWith(parseStrings(bytes, ParserConfig()), StringsFailure.InvalidEncoding)
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
    fun pairedSurrogateEscapeDecodesSupplementaryCodepoint() {
        // Apple writes emoji as paired \U<high>\U<low>. \UD83D\UDE00 is U+1F600 (😀);
        // the resulting Kotlin String is well-formed UTF-16 (length 2, 4 UTF-8 bytes).
        val source = "\"emoji\" = \"\\UD83D\\UDE00\";"
        val ok = parseStrings(source.toByteArray(), ParserConfig()).asOk()
        val value = ok.strings.entries["emoji"]
        assertThat(value).isEqualTo("😀")
        assertThat(value).isEqualTo("😀")
        assertThat(value!!.toByteArray(StandardCharsets.UTF_8)).hasLength(4)
    }

    @Test
    fun loneHighSurrogateEscapeIsRejected() {
        // \UD800 alone would produce malformed UTF-16; reject as BadEscape rather
        // than embed it and surprise downstream UTF-8 boundaries with U+FFFD or
        // CharacterCodingException.
        val source = "\"k\" = \"\\UD800\";"
        assertFailedWith(parseStrings(source.toByteArray(), ParserConfig()), StringsFailure.BadEscape)
    }

    @Test
    fun loneLowSurrogateEscapeIsRejected() {
        // \UDC00 without a preceding high surrogate is also malformed.
        val source = "\"k\" = \"\\UDC00\";"
        assertFailedWith(parseStrings(source.toByteArray(), ParserConfig()), StringsFailure.BadEscape)
    }

    @Test
    fun highSurrogateFollowedByNonSurrogateIsRejected() {
        // After a high surrogate, only \U<low> is acceptable; a literal char or a
        // non-low \U escape must surface as BadEscape, not silently truncate.
        val source = "\"k\" = \"\\UD83Dx\";"
        assertFailedWith(parseStrings(source.toByteArray(), ParserConfig()), StringsFailure.BadEscape)
    }

    @Test
    fun unrecognizedEscapeReturnsFailed() {
        // \x is not an Apple-supported escape; the strict reader rejects it rather
        // than silently emitting `x`, which would let an attacker smuggle bytes past
        // a downstream renderer that thinks the parser stripped escapes.
        val source = "\"k\" = \"a\\xb\";"
        assertFailedWith(parseStrings(source.toByteArray(), ParserConfig()), StringsFailure.BadEscape)
    }

    @Test
    fun unterminatedQuotedStringReturnsUnterminatedString() {
        val source = "\"k\" = \"unterminated"
        assertFailedWith(parseStrings(source.toByteArray(), ParserConfig()), StringsFailure.UnterminatedString)
    }

    @Test
    fun unterminatedBlockCommentReturnsUnterminatedComment() {
        val source = "/* never closed"
        assertFailedWith(parseStrings(source.toByteArray(), ParserConfig()), StringsFailure.UnterminatedComment)
    }

    @Test
    fun missingEqualsReturnsBadStructure() {
        val source = "\"k\" \"v\";"
        assertFailedWith(parseStrings(source.toByteArray(), ParserConfig()), StringsFailure.BadStructure)
    }

    @Test
    fun missingSemicolonReturnsBadStructure() {
        val source = "\"k\" = \"v\""
        assertFailedWith(parseStrings(source.toByteArray(), ParserConfig()), StringsFailure.BadStructure)
    }

    @Test
    fun perValueByteCapTrip() {
        val cfg = ParserConfig(maxJsonStringBytes = 8)
        val source = "\"k\" = \"" + "x".repeat(16) + "\";"
        assertFailedWith(parseStrings(source.toByteArray(), cfg), StringsFailure.ValueTooLong)
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
        assertFailedWith(parseStrings(source.toByteArray(), cfg), StringsFailure.ValueTooLong)
    }

    @Test
    fun surrogatePairBytesAreCountedConservativelyAgainstCap() {
        // 😀 is 4 bytes UTF-8; utf8Bytes() reports 3 per surrogate half = 6 total
        // (a deliberate overcount). cap=4 → rejected (stricter than reality, fine);
        // cap=6 → accepted (matches the conservative bound).
        val sourceFmt = { cap: Int -> Pair(ParserConfig(maxJsonStringBytes = cap), "\"k\" = \"\\UD83D\\UDE00\";") }
        val (tightCfg, tightSrc) = sourceFmt(4)
        assertFailedWith(parseStrings(tightSrc.toByteArray(), tightCfg), StringsFailure.ValueTooLong)
        val (looseCfg, looseSrc) = sourceFmt(6)
        val ok = parseStrings(looseSrc.toByteArray(), looseCfg).asOk()
        assertThat(ok.strings.entries["k"]).isEqualTo("😀")
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
        // as Ok(empty), not InvalidEncoding — the BOM is a marker, not content.
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val ok = parseStrings(bom, ParserConfig()).asOk()
        assertThat(ok.strings.entries).isEmpty()
    }

    @Test
    fun lineCommentTerminatesOnCarriageReturn() {
        // Classic-Mac CR-only line endings: a line comment must not swallow the
        // following entry just because there is no \n. Mixed CR / CRLF shows up
        // when a tool re-saves with the wrong EOL convention.
        val source = "// line comment\r\"k\" = \"v\";"
        val ok = parseStrings(source.toByteArray(), ParserConfig()).asOk()
        assertThat(ok.strings.entries).containsExactly("k", "v")
    }

    @Test
    fun emptyKeyParses() {
        // Apple accepts "" = "v"; — degenerate but legal. We match.
        val source = "\"\" = \"v\";"
        val ok = parseStrings(source.toByteArray(), ParserConfig()).asOk()
        assertThat(ok.strings.entries[""]).isEqualTo("v")
    }

    @Test
    fun whitespaceBeforeSemicolonIsAccepted() {
        // consumeAfterWhitespace skips ws/comments before the delimiter; verify
        // the path is exercised for `;` as well as `=`.
        val source = "\"k\" = \"v\" ;"
        val ok = parseStrings(source.toByteArray(), ParserConfig()).asOk()
        assertThat(ok.strings.entries).containsExactly("k", "v")
    }

    private fun StringsResult.asOk(): StringsResult.Ok {
        assertThat(this).isInstanceOf(StringsResult.Ok::class.java)
        return this as StringsResult.Ok
    }

    private fun assertFailedWith(
        actual: StringsResult,
        expected: StringsFailure,
    ) {
        assertThat(actual).isInstanceOf(StringsResult.Failed::class.java)
        val failure = (actual as StringsResult.Failed).failure
        assertWithMessage("expected StringsFailure=$expected, got $failure").that(failure).isEqualTo(expected)
    }
}
