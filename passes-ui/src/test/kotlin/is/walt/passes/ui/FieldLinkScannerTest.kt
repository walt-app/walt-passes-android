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
        // chroniques pass back-field "ticketNoBack" value: "52311919".
        val spans = FieldLinkScanner.scan("52311919", source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun rejectsBareSevenDigitOrderNumberAsPhone() {
        // chroniques pass back-field "orderNoBack" value: "5847559".
        val spans = FieldLinkScanner.scan("5847559", source)
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
        val spans = FieldLinkScanner.scan("Order 52311919 placed.", source)
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
     * Tripwire for ReDoS / catastrophic-backtracking in the phone-number regex. The
     * phone pattern uses a quantified character class with optional digit boundary;
     * a hostile back-field could in principle force quadratic-or-worse backtracking.
     * This test feeds a 4 KB digit-soup field that has no valid match anchor.
     *
     * The assertion is on the FASTEST of several timed runs after a JIT warm-up
     * ([fastestScanMillis]), not a single cold sample: catastrophic backtracking is
     * super-linear and blows the budget by orders of magnitude on EVERY run, so the min
     * still catches it — while a cold-JIT compile of the regex/Cf-stripping path, a GC
     * pause, or a contended CI core can no longer trip a genuinely linear scan. Real
     * linear-ish behavior settles in single-digit milliseconds; the generous
     * [REDOS_BUDGET_MS] budget leaves two orders of magnitude of headroom.
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

    /**
     * Scans [input] several times and returns the fastest run in milliseconds, after an
     * untimed JIT warm-up. The min over repeats reflects the scan's algorithmic cost rather
     * than one-off scheduling/GC noise, which is what the ReDoS tripwires need: a super-linear
     * blowup is slow on every run, so it survives the min; transient slowness on an otherwise
     * linear scan does not.
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
        // safe to render. This is the legitimate way for a pass author to include
        // non-ASCII data in a URL — the renderer never sees the decoded form.
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

    @Test
    fun containsRenderingHazardAcceptsPlainAscii() {
        assertThat(FieldLinkScanner.containsRenderingHazard("https://example.com/path"))
            .isFalse()
    }

    private companion object {
        // ReDoS tripwire timing knobs. Warm-up removes cold-JIT cost; the min over timed
        // runs removes one-off GC/scheduling noise; the budget is deliberately generous —
        // a genuinely linear scan settles in single-digit ms, while catastrophic
        // backtracking would take seconds-or-worse on every run, far past this ceiling.
        const val REDOS_WARMUP_RUNS = 3
        const val REDOS_TIMED_RUNS = 5
        const val REDOS_BUDGET_MS = 1_000L
    }
}
