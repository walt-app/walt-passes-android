package `is`.walt.passes.ui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class FieldLinkScannerTest {

    private val source = SourceField(
        fieldKey = "support_url",
        fieldLabel = "Support",
        organizationName = "Acme",
    )

    @Test
    fun detectsHttpsUrl() {
        val spans = FieldLinkScanner.scan(
            "Visit https://acme.example/help for assistance.",
            source,
        )
        assertThat(spans).hasSize(1)
        val span = spans.single()
        assertThat(span.intent).isInstanceOf(B3UrlIntent::class.java)
        assertThat((span.intent as B3UrlIntent).url).isEqualTo("https://acme.example/help")
    }

    @Test
    fun detectsHttpUrl() {
        val spans = FieldLinkScanner.scan("http://example.com/x", source)
        assertThat(spans.single().intent).isInstanceOf(B3UrlIntent::class.java)
    }

    @Test
    fun doesNotDetectSchemeLessUrl() {
        val spans = FieldLinkScanner.scan("acme.example", source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun detectsEmail() {
        val spans = FieldLinkScanner.scan("Email support@acme.example for help", source)
        assertThat(spans.single().intent).isInstanceOf(EmailIntent::class.java)
        assertThat((spans.single().intent as EmailIntent).emailAddress)
            .isEqualTo("support@acme.example")
    }

    @Test
    fun emailsBesidePunctuationAndInProseKeepExactRanges() {
        listOf(
            "(support@acme.example)",
            "<support@acme.example>",
            "\"support@acme.example\"",
            "Mail: support@acme.example.",
            "Mail support@acme.example, or call.",
            "Write to support@acme.example!",
            "Mail support@acme.example\nThanks",
        ).forEach { text ->
            assertExactSpans(text, "support@acme.example" to EmailIntent::class.java)
        }
    }

    @Test
    fun emailLocalPartTakesTheWholeRun() {
        listOf(
            "first.last+tag@mail.acme.example",
            "a_b%c-d@acme-1.example",
            "Ops.Team@Acme.EXAMPLE",
            "x@acme.example",
        ).forEach { email ->
            assertExactSpans("Contact $email today", email to EmailIntent::class.java)
        }
    }

    @Test
    fun emailDomainEndsAtTheLastValidTld() {
        assertExactSpans("a@b.example..", "a@b.example" to EmailIntent::class.java)
        assertExactSpans("a@b.example.c", "a@b.example" to EmailIntent::class.java)
        assertExactSpans("a@b.example-1", "a@b.example" to EmailIntent::class.java)
        assertExactSpans("a@b.c.de.f1", "a@b.c.de" to EmailIntent::class.java)
    }

    @Test
    fun emailWithoutUsableDomainDoesNotLink() {
        listOf("a@", "@acme.example", "a@acme", "a@acme.e", "a@@acme.example", "a@.").forEach { text ->
            assertThat(FieldLinkScanner.scan(text, source)).isEmpty()
        }
    }

    @Test
    fun separatedEmailsAllLink() {
        assertExactSpans(
            "a@b.example, c@d.example;e@f.example g@h.example",
            "a@b.example" to EmailIntent::class.java,
            "c@d.example" to EmailIntent::class.java,
            "e@f.example" to EmailIntent::class.java,
            "g@h.example" to EmailIntent::class.java,
        )
    }

    @Test
    fun emailNeverStartsInsideAPreviousAddress() {
        // A match may not start mid-run or right after an `@`, so only the first address links.
        mapOf(
            "a@b.example-c@d.example" to "a@b.example",
            "a@b.example.c@d.example" to "a@b.example",
            "a@b.ex1@d.example" to "a@b.ex",
            "a@b.co1x@y.co2z@w.co" to "a@b.co",
            "a@b.example-c@d.example@e.example" to "a@b.example",
        ).forEach { (text, first) ->
            assertExactSpans(text, first to EmailIntent::class.java)
        }
        assertThat(FieldLinkScanner.scan("a@b@c.example", source)).isEmpty()
    }

    @Test
    fun detectsInternationalPhone() {
        val spans = FieldLinkScanner.scan("Call us at +1 (555) 123-4567 anytime.", source)
        assertThat(spans.single().intent).isInstanceOf(PhoneIntent::class.java)
        assertThat((spans.single().intent as PhoneIntent).phoneNumber)
            .isEqualTo("+1 (555) 123-4567")
    }

    @Test
    fun rejectsShortDigitRunsAsPhone() {
        // 5 digits: too short to be a phone number
        val spans = FieldLinkScanner.scan("Promo code 12345", source)
        assertThat(spans).isEmpty()
    }

    // -- wpass-536: bare digit runs without formatting hints ----------------------
    //
    // The chroniques (pass.com.tixly) regression. An 8-digit ticket number rendered
    // tappable, surfacing a "Call this number?" prompt on a reference value the user
    // never expected to be a phone number. The fix requires at least one formatting
    // hint (+, dash, space, paren) before classifying a digit run as a phone number,
    // matching Apple's data-detector behavior.

    @Test
    fun rejectsBareEightDigitTicketNumberAsPhone() {
        // A bare 8-digit ticket number, the shape real passes put in a back field.
        val spans = FieldLinkScanner.scan("10203040", source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun rejectsBareSevenDigitOrderNumberAsPhone() {
        // A bare 7-digit order number, the shape real passes put in a back field.
        val spans = FieldLinkScanner.scan("1020304", source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun rejectsLongBareDigitRunAsPhone() {
        // Barcode payload, member ID, etc. — long bare digit string with no formatting
        // hint must never fire the phone detector.
        val spans = FieldLinkScanner.scan("123456789012", source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun rejectsBareDigitRunAdjacentToProseAsPhone() {
        val spans = FieldLinkScanner.scan("Order 10203040 placed.", source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun acceptsPhoneWithOnlySpaceHint() {
        val spans = FieldLinkScanner.scan("Call 555 123 4567 today.", source)
        assertThat(spans).hasSize(1)
        assertThat(spans.single().intent).isInstanceOf(PhoneIntent::class.java)
        assertThat((spans.single().intent as PhoneIntent).phoneNumber).isEqualTo("555 123 4567")
    }

    @Test
    fun acceptsPhoneWithOnlyDashHint() {
        val spans = FieldLinkScanner.scan("555-123-4567", source)
        assertThat(spans).hasSize(1)
        assertThat(spans.single().intent).isInstanceOf(PhoneIntent::class.java)
    }

    @Test
    fun acceptsPhoneWithOnlyParenHint() {
        val spans = FieldLinkScanner.scan("Call (5551234567) now.", source)
        assertThat(spans).hasSize(1)
        assertThat(spans.single().intent).isInstanceOf(PhoneIntent::class.java)
    }

    @Test
    fun acceptsPhoneWithOnlyPlusHint() {
        val spans = FieldLinkScanner.scan("+15551234567", source)
        assertThat(spans).hasSize(1)
        assertThat(spans.single().intent).isInstanceOf(PhoneIntent::class.java)
        assertThat((spans.single().intent as PhoneIntent).phoneNumber).isEqualTo("+15551234567")
    }

    @Test
    fun emailInsideUrlIsNotDoubleClaimed() {
        val spans = FieldLinkScanner.scan("https://x@example.com/path", source)
        assertThat(spans).hasSize(1)
        assertThat(spans.single().intent).isInstanceOf(B3UrlIntent::class.java)
    }

    @Test
    fun multipleLinksReturnInOrder() {
        val text = "Site https://a.example or call +1-555-123-4567 or mailto:b@c.example."
        val spans = FieldLinkScanner.scan(text, source)
        // URL, phone, email (order in the source string).
        assertThat(spans.map { it.intent::class.simpleName }).containsExactly(
            "B3UrlIntent",
            "PhoneIntent",
            "EmailIntent",
        ).inOrder()
    }

    @Test
    fun targetStringIsVerbatim() {
        val text = "Reach me at  +44  20  7946  0958 ."
        val spans = FieldLinkScanner.scan(text, source)
        val intent = spans.single().intent as PhoneIntent
        // Trim is applied at the boundary so only the surrounding whitespace is removed;
        // internal whitespace is preserved verbatim, matching what the user sees.
        assertThat(intent.phoneNumber).isEqualTo("+44  20  7946  0958")
    }

    @Test
    fun sourceFieldIsCarriedThrough() {
        val spans = FieldLinkScanner.scan("https://example.com", source)
        assertThat(spans.single().intent.sourceField).isEqualTo(source)
    }

    /**
     * Tripwire for ReDoS / catastrophic backtracking in the phone-number regex: a 4 KB
     * digit-soup field with no valid match anchor. See [fastestScanMillis] for the timing
     * method.
     */
    @Test
    fun phoneScanCompletesQuicklyOnPathologicalInput() {
        val pathological = buildString {
            repeat(4096) { append((it % 10).digitToChar()) }
        }
        assertThat(fastestScanMillis(pathological)).isLessThan(REDOS_BUDGET_MS)
    }

    @Test
    fun mixedAlphaDigitSoupCompletesQuickly() {
        val pathological = buildString {
            repeat(2048) {
                append((it % 10).digitToChar())
                append(' ')
                append('-')
            }
        }
        assertThat(fastestScanMillis(pathological)).isLessThan(REDOS_BUDGET_MS)
    }

    @Test
    fun phoneDenseFieldScansInLinearTime() {
        // Every `+` is a hint and starts a new 8-digit match inside one long run.
        assertThat(fastestScanMillis("+12345678 ".repeat(30_000))).isLessThan(REDOS_BUDGET_MS)
    }

    @Test
    fun mixedDigitRunScansInLinearTime() {
        // One run spanning the field, mixed by U+0663, holding 30k rejected phone matches.
        assertThat(fastestScanMillis("1234-5678 \u0663 ".repeat(30_000))).isLessThan(REDOS_BUDGET_MS)
    }

    @Test
    fun emailLocalPartRunWithoutAtScansInLinearTime() {
        assertThat(fastestScanMillis("a".repeat(200_000))).isLessThan(REDOS_BUDGET_MS)
    }

    @Test
    fun emailDomainWithoutTldScansInLinearTime() {
        // The domain never ends in a TLD, and is itself one long local-part run.
        assertThat(fastestScanMillis("a@" + "a.".repeat(100_000))).isLessThan(REDOS_BUDGET_MS)
    }

    @Test
    fun phoneShapedLocalPartRunScansInLinearTime() {
        // One unbroken local-part run without `@`, holding 22k phone matches.
        assertThat(fastestScanMillis("+12345678".repeat(22_000))).isLessThan(REDOS_BUDGET_MS)
    }

    /**
     * Scans [input] several times and returns the FASTEST run in milliseconds — the timing
     * method the ReDoS tripwires assert on. The untimed warm-up absorbs the cold-JIT compile
     * of the regex / Cf-stripping path, and the min over timed runs absorbs one-off GC /
     * scheduling noise, so a genuinely linear scan stays in single-digit milliseconds even on
     * a contended CI core. Catastrophic backtracking is super-linear — slow on every run — so
     * it survives the min and still blows [REDOS_BUDGET_MS] by orders of magnitude.
     */
    private fun fastestScanMillis(input: String): Long {
        repeat(REDOS_WARMUP_RUNS) { FieldLinkScanner.scan(input, source) }
        var bestMs = Long.MAX_VALUE
        repeat(REDOS_TIMED_RUNS) {
            val start = System.nanoTime()
            FieldLinkScanner.scan(input, source)
            bestMs = minOf(bestMs, (System.nanoTime() - start) / 1_000_000)
        }
        return bestMs
    }

    // -- Bidi / formatting-control rejection ------------------------------------
    //
    // The auditor scenario: an adversary embeds U+202E (Right-to-Left Override) in a
    // URL so the sheet renders one host visually while `Uri.parse` resolves another.
    // The scanner MUST refuse to detect such URLs as URLs, and the value handed into
    // `B3UrlIntent.url` MUST be byte-identical to the substring that matched the
    // regex (no silent Cf-stripping, which would itself violate the
    // "displayed string equals actionable string" claim by changing the bytes the
    // host opens versus what the user saw).

    @Test
    fun urlContainingRightToLeftOverrideIsRejected() {
        val attack = "https://attacker.example/‮gpj.elgoog//:sptth"
        val spans = FieldLinkScanner.scan(attack, source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun urlContainingZeroWidthSpaceIsRejected() {
        val attack = "https://goog​le.com/path"
        val spans = FieldLinkScanner.scan(attack, source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun urlContainingLeftToRightMarkIsRejected() {
        val attack = "https://example.com/‎path"
        val spans = FieldLinkScanner.scan(attack, source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun urlContainingArabicLetterMarkIsRejected() {
        val attack = "https://example.com/؜path"
        val spans = FieldLinkScanner.scan(attack, source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun urlContainingControlCharIsRejected() {
        val attack = "https://example.com/beep"
        val spans = FieldLinkScanner.scan(attack, source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun emailContainingBidiCharIsRejected() {
        // Email's character class is already ASCII-only so the regex itself excludes
        // bidi chars, but lock the property via the post-filter so a future regex
        // relaxation cannot regress.
        val attack = "support‮@example.com"
        val spans = FieldLinkScanner.scan(attack, source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun phoneContainingBidiCharIsRejected() {
        // Phone is similarly ASCII-only; same lock.
        val attack = "+1 555‮ 123 4567"
        val spans = FieldLinkScanner.scan(attack, source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun cleanAdjacentToBidiHostileSurfacesNothingFromTheField() {
        // Field-level rejection: if any rendering hazard appears anywhere in the
        // field, ALL detection is dropped. The user is forced to act manually
        // outside the auto-link path. The clean URL is collateral damage; the
        // alternative (still surfacing the clean one) leaves the user reading the
        // bidi-reordered field and tapping a link with no clear association.
        val mixed = "Visit https://example.com or https://attacker.example/‮gpj.elgoog//:sptth"
        val spans = FieldLinkScanner.scan(mixed, source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun urlBytesAreVerbatimNoCfStripping() {
        // A clean URL passes through unchanged. The explicit byte-equality check
        // guards the trust claim that the displayed string equals the actionable
        // string — if the scanner ever sanitizes inputs (e.g. by stripping Cf
        // characters), the displayed and actioned strings would diverge.
        val clean = "https://example.com/path?q=1"
        val spans = FieldLinkScanner.scan(clean, source)
        val intent = spans.single().intent as B3UrlIntent
        assertThat(intent.url).isEqualTo(clean)
    }

    @Test
    fun percentEncodedBidiInUrlIsAccepted() {
        // %E2%80%AE is the percent-encoding of U+202E. After percent-decoding, the
        // URL would contain a bidi char; pre-decoding, the bytes are pure ASCII and
        // safe to render. The renderer never sees the decoded form.
        val url = "https://example.com/%E2%80%AEsomething"
        val spans = FieldLinkScanner.scan(url, source)
        assertThat(spans).hasSize(1)
        val intent = spans.single().intent as B3UrlIntent
        assertThat(intent.url).isEqualTo(url)
    }

    @Test
    fun containsRenderingHazardCoversFormatAndControlCategories() {
        // Direct check on the helper so the categorization is locked even if regexes
        // change. The category enum is the source of truth.
        listOf(
            "‮", // Right-to-Left Override (Cf)
            "‭", // Left-to-Right Override (Cf)
            "⁦", // Left-to-Right Isolate (Cf)
            "⁧", // Right-to-Left Isolate (Cf)
            "⁨", // First Strong Isolate (Cf)
            "⁩", // Pop Directional Isolate (Cf)
            "​", // Zero Width Space (Cf)
            "‎", // Left-to-Right Mark (Cf)
            "‏", // Right-to-Left Mark (Cf)
            "؜", // Arabic Letter Mark (Cf)
            "﻿", // Zero-Width No-Break Space / BOM (Cf)
            " ", // NUL (Cc)
            "", // BEL (Cc)
            "", // ESC (Cc)
        ).forEach { hazard ->
            val codepoint = "U+%04X".format(hazard.first().code)
            assertWithMessage("$codepoint must be flagged as rendering hazard")
                .that(FieldLinkScanner.containsRenderingHazard("safe$hazard"))
                .isTrue()
        }
    }

    // -- Multi-line fields -------------------------------------------------------
    //
    // Line breaks and tabs separate lines and columns in real back fields. They must
    // not suppress links, but no link target may contain one.

    @Test
    fun multiLineFieldYieldsEveryLinkWithExactRanges() {
        val text = "Terms apply.\nHelp: https://acme.example/help\nMail support@acme.example\n" +
            "Phone +1 555 123 4567\nThanks"
        assertExactSpans(
            text,
            "https://acme.example/help" to B3UrlIntent::class.java,
            "support@acme.example" to EmailIntent::class.java,
            "+1 555 123 4567" to PhoneIntent::class.java,
        )
    }

    @Test
    fun crlfFieldYieldsEveryLinkWithExactRanges() {
        val text = "Terms apply.\r\nhttps://acme.example/help\r\nsupport@acme.example\r\n" +
            "+1 555 123 4567\r\n"
        assertExactSpans(
            text,
            "https://acme.example/help" to B3UrlIntent::class.java,
            "support@acme.example" to EmailIntent::class.java,
            "+1 555 123 4567" to PhoneIntent::class.java,
        )
    }

    @Test
    fun tabSeparatedFieldYieldsEveryLinkWithExactRanges() {
        val text = "Web:\thttps://acme.example/help\tMail:\tsupport@acme.example\tTel:\t+1 555 123 4567"
        assertExactSpans(
            text,
            "https://acme.example/help" to B3UrlIntent::class.java,
            "support@acme.example" to EmailIntent::class.java,
            "+1 555 123 4567" to PhoneIntent::class.java,
        )
    }

    @Test
    fun urlStopsAtLineBreak() {
        val spans = FieldLinkScanner.scan("https://acme.example/a\nnext line", source)
        assertThat((spans.single().intent as B3UrlIntent).url).isEqualTo("https://acme.example/a")
    }

    @Test
    fun phoneStopsAtLineBreak() {
        assertExactSpans("Tel +1 555 123 4567\n10203040", "+1 555 123 4567" to PhoneIntent::class.java)
    }

    @Test
    fun phoneAfterLineEndingInDigitIsFound() {
        assertExactSpans("Order 77\r\n555-123-4567", "555-123-4567" to PhoneIntent::class.java)
    }

    @Test
    fun phoneAfterTabFollowingDigitIsFound() {
        assertExactSpans("Seat 4\t555-123-4567", "555-123-4567" to PhoneIntent::class.java)
    }

    @Test
    fun urlEndsBeforeUnicodeWhitespace() {
        // The URL class excludes the White_Space set, as `\s` does on Android. The other
        // six members (VT, FF, lone CR, NEL, U+2028, U+2029) are field hazards.
        (listOf('\t', '\n', ' ', '\u00A0', '\u1680') + ('\u2000'..'\u200A') + listOf('\u202F', '\u205F', '\u3000'))
            .forEach { space ->
                assertExactSpans("https://acme.example/a${space}b", "https://acme.example/a" to B3UrlIntent::class.java)
            }
    }

    @Test
    fun whiteSpaceFieldHazardsYieldNoLinks() {
        listOf('\u000B', '\u000C', '\r', '\u0085', '\u2028', '\u2029').forEach { space ->
            val codepoint = "U+%04X".format(space.code)
            assertWithMessage("$codepoint must reject the field")
                .that(FieldLinkScanner.scan("Help${space}https://acme.example/help", source))
                .isEmpty()
        }
    }

    @Test
    fun loneCarriageReturnRejectsTheField() {
        // A lone CR may render with no width, hiding where a link ends.
        assertThat(FieldLinkScanner.scan("support@bank.example\rm", source)).isEmpty()
        assertThat(FieldLinkScanner.scan("Line\rhttps://acme.example/help", source)).isEmpty()
    }

    @Test
    fun urlContainingSupplementaryTagCharIsRejected() {
        // U+E0001 LANGUAGE TAG, a Cf codepoint encoded as a surrogate pair.
        val spans = FieldLinkScanner.scan("https://acme.example/\uDB40\uDC01path", source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun supplementaryFormatCharElsewhereRejectsTheWholeField() {
        // U+E0020 TAG SPACE on its own line, away from the link.
        val spans = FieldLinkScanner.scan("Note \uDB40\uDC20\nhttps://acme.example/help", source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun supplementaryFormatCharsAreHazardsAtBothLevels() {
        listOf(
            "\uDB40\uDC01", // U+E0001 LANGUAGE TAG
            "\uDB40\uDC20", // U+E0020 TAG SPACE
            "\uD82F\uDCA0", // U+1BCA0 SHORTHAND FORMAT LETTER OVERLAP
            "\uD834\uDD73", // U+1D173 MUSICAL SYMBOL BEGIN BEAM
        ).forEach { hazard ->
            val codepoint = "U+%04X".format(hazard.codePointAt(0))
            assertWithMessage("$codepoint must be a field hazard")
                .that(FieldLinkScanner.containsFieldHazard("safe$hazard"))
                .isTrue()
            assertWithMessage("$codepoint must be a match-level hazard")
                .that(FieldLinkScanner.containsRenderingHazard("safe$hazard"))
                .isTrue()
        }
    }

    @Test
    fun multiLineUrlContainingBidiOverrideIsRejected() {
        val spans = FieldLinkScanner.scan(
            "Line one\nhttps://attacker.example/\u202Egpj.elgoog//:sptth\nLine three",
            source,
        )
        assertThat(spans).isEmpty()
    }

    @Test
    fun multiLineUrlContainingZeroWidthSpaceIsRejected() {
        val spans = FieldLinkScanner.scan("Help:\nhttps://goog\u200Ble.com/path\n", source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun bidiControlAdjacentToMatchOnMultiLineFieldRejectsTheField() {
        val spans = FieldLinkScanner.scan("Help:\n\u202Ehttps://acme.example/help\n", source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun bidiControlOnAnotherLineStillRejectsTheWholeField() {
        // Line breaks do not split the field into independent trust units.
        val spans = FieldLinkScanner.scan(
            "Note \u202Ereversed\nhttps://acme.example/help\nsupport@acme.example",
            source,
        )
        assertThat(spans).isEmpty()
    }

    // -- Engine-independent classes ---------------------------------------------
    //
    // `\s` and `\d` are Unicode-aware on Android but ASCII-only on the JVM, so the
    // patterns spell their classes out; these cases pin what the device does.

    @Test
    fun phoneWithNarrowNoBreakSpacesIsOneSpan() {
        val text = "Tel +33\u202F1\u202F23\u202F45\u202F67\u202F89."
        assertExactSpans(text, "+33\u202F1\u202F23\u202F45\u202F67\u202F89" to PhoneIntent::class.java)
    }

    @Test
    fun phoneWithNoBreakSpacesIsOneSpan() {
        val text = "Tel +33\u00A01\u00A023\u00A045\u00A067\u00A089."
        assertExactSpans(text, "+33\u00A01\u00A023\u00A045\u00A067\u00A089" to PhoneIntent::class.java)
    }

    @Test
    fun phoneSpacedOnlyWithNoBreakSpacesAndNoPlusStaysUnlinked() {
        assertThat(FieldLinkScanner.scan("Ref 555\u00A0123\u00A04567", source)).isEmpty()
    }

    @Test
    fun phoneRunMixingNonAsciiDigitsDoesNotLink() {
        listOf(
            "Call 0800 555 010\uD835\uDFD7 now", // trailing U+1D7D7 MATHEMATICAL BOLD DIGIT NINE
            "Call \uFF11800-555-0100 now", // leading fullwidth one
            "Call \uD835\uDFD7800-555-0100 now", // leading U+1D7D7 MATHEMATICAL BOLD DIGIT NINE
            "Call 555-123-4567\u0663 now", // trailing Arabic-Indic three
        ).forEach { text ->
            assertWithMessage(text).that(FieldLinkScanner.scan(text, source)).isEmpty()
        }
    }

    @Test
    fun nonAsciiDigitOutsideThePhoneRunKeepsThePhone() {
        listOf(
            "Ref \u0663\u0664\u0665\n+1 555 123 4567", // a line break ends the run
            "\u0663 x +1 555 123 4567", // a letter ends the run
            "\u0663\uD83D\uDE00+1 555 123 4567", // a supplementary non-run char ends the run
        ).forEach { text ->
            val spans = FieldLinkScanner.scan(text, source)
            assertWithMessage(text).that(spans).hasSize(1)
            assertWithMessage(text).that((spans.single().intent as PhoneIntent).phoneNumber)
                .isEqualTo("+1 555 123 4567")
        }
    }

    @Test
    fun phoneInsideUrlOrEmailIsNotLinkedTwice() {
        val inUrl = FieldLinkScanner.scan("https://acme.example/call/555-123-4567", source)
        assertThat(inUrl.single().intent).isInstanceOf(B3UrlIntent::class.java)

        val inEmail = FieldLinkScanner.scan("555-123-4567@acme.example", source)
        assertThat(inEmail.single().intent).isInstanceOf(EmailIntent::class.java)

        val text = "+1 555 123 4567 https://x.example/555-123-4567 a@b.example +1 555 765 4321"
        val kinds = FieldLinkScanner.scan(text, source).map { it.intent::class.java }
        assertThat(kinds).containsExactly(
            PhoneIntent::class.java,
            B3UrlIntent::class.java,
            EmailIntent::class.java,
            PhoneIntent::class.java,
        ).inOrder()
    }

    @Test
    fun nonAsciiDigitRunsDoNotLink() {
        listOf(
            "+\u0669\u0667\u0661 \u0664 \u0663\u0666\u0666 \u0668\u0668\u0668\u0668", // Arabic-Indic
            "+\uFF11 \uFF15\uFF15\uFF15 \uFF11\uFF12\uFF13 \uFF14\uFF15\uFF16\uFF17", // fullwidth
        ).forEach { text ->
            assertThat(FieldLinkScanner.scan(text, source)).isEmpty()
        }
    }

    @Test
    fun fieldHazardAllowsOnlyLineBreaksAndTab() {
        listOf("\n", "\t", "\r\n", "\r\n\r\n").forEach { whitespace ->
            assertThat(FieldLinkScanner.containsFieldHazard("a${whitespace}b")).isFalse()
        }
        HAZARDS.forEach { hazard ->
            val codepoint = "U+%04X".format(hazard.code)
            assertWithMessage("$codepoint must be a field hazard")
                .that(FieldLinkScanner.containsFieldHazard("safe\n$hazard"))
                .isTrue()
        }
        assertThat(FieldLinkScanner.containsFieldHazard("a\r\r\nb")).isTrue()
    }

    @Test
    fun renderingHazardStillFlagsLineBreaksAndTab() {
        listOf('\n', '\r', '\t', '\u2028', '\u2029').forEach { whitespace ->
            val codepoint = "U+%04X".format(whitespace.code)
            assertWithMessage("$codepoint must be a match-level hazard")
                .that(FieldLinkScanner.containsRenderingHazard("a${whitespace}b"))
                .isTrue()
        }
    }

    private fun assertExactSpans(text: String, vararg expected: Pair<String, Class<out SecurityIntent>>) {
        val spans = FieldLinkScanner.scan(text, source)
        assertThat(spans).hasSize(expected.size)
        spans.zip(expected).forEach { (span, want) ->
            val (target, type) = want
            val start = text.indexOf(target)
            assertThat(span.start).isEqualTo(start)
            assertThat(span.endExclusive).isEqualTo(start + target.length)
            assertThat(text.substring(span.start, span.endExclusive)).isEqualTo(target)
            assertThat(span.intent).isInstanceOf(type)
            val actionable = when (val intent = span.intent) {
                is B3UrlIntent -> intent.url
                is EmailIntent -> intent.emailAddress
                is PhoneIntent -> intent.phoneNumber
            }
            assertThat(actionable).isEqualTo(target)
        }
    }

    @Test
    fun containsRenderingHazardAcceptsPlainAscii() {
        assertThat(FieldLinkScanner.containsRenderingHazard("https://example.com/path"))
            .isFalse()
    }

    private companion object {
        // Cf, Cc (bar line feed and tab) and separator codepoints that must reject a field.
        val HAZARDS = listOf(
            '\r', '\u2028', '\u2029',
            '\u202E', '\u202D', '\u2066', '\u2067', '\u2068', '\u2069', '\u200B',
            '\u200E', '\u200F', '\u061C', '\uFEFF', '\u0000', '\u0007', '\u001B',
            '\u000B', '\u000C', '\u007F', '\u0085',
        )

        // ReDoS tripwire timing knobs (warm-up runs, timed runs, budget); see [fastestScanMillis].
        const val REDOS_WARMUP_RUNS = 3
        const val REDOS_TIMED_RUNS = 5
        const val REDOS_BUDGET_MS = 1_000L
    }
}
