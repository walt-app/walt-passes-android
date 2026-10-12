package `is`.walt.passes.ui

/**
 * Detects URLs, phone numbers, and email addresses in pass back-field values. Returns
 * a list of [LinkSpan] entries with their indexes into the original string so the
 * Compose layer can render them as tappable affordances pointing at the corresponding
 * [SecurityIntent].
 *
 * Trust-claim relevance: the scanner extracts the *exact substring* that becomes the
 * intent's target. There is no normalization (no scheme injection, no auto-https, no
 * "support@example.com" rewritten to "mailto:support@example.com" — the `mailto:`
 * prefix is added by the host's outbound `Intent` construction, not by this scanner).
 * The string the user sees in the confirmation sheet is the same string this scanner
 * pulled out of the pass.
 *
 * Intentionally conservative: prefers false negatives over false positives.
 *
 * - A URL without an `http`/`https` scheme is not detected as a URL.
 * - A URL runs from the scheme to the first Unicode White_Space character or one of
 *   `<>"'()`. It is not limited to ASCII: raw non-ASCII characters, including in the
 *   host, are part of the match. Raw format or control characters inside the run
 *   reject the URL outright (see the match-level check below). This defends against
 *   the bidi spoofing class — e.g. `https://attacker.example/‮gpj.elgoog//:sptth`
 *   rendering visually as a Google asset URL while `Uri.parse` resolves to
 *   `attacker.example`. Tested in `FieldLinkScannerTest`.
 * - A phone number requires at least seven digits AND at least one formatting hint
 *   (a leading `+`, an embedded space, dash, or parenthesis) before it is recognised.
 *   Bare digit runs — ticket numbers, order IDs, member numbers, barcode payloads —
 *   are left as plain text. This mirrors Apple's data-detector behavior in iOS Wallet
 *   and prevents two failure modes: (a) accidental "Call this number?" prompts on
 *   every numeric field of a real pass, and (b) a malicious pass embedding a
 *   premium-rate dial string in a value that the user reads as a reference number.
 * - An email requires an `@` with non-empty ASCII local and domain parts.
 *
 * Rendering hazards are checked at two levels:
 *
 * - Field level ([containsFieldHazard]): a field containing any Unicode formatting
 *   (Cf) or control (Cc) codepoint surfaces no links at all, except that line feed,
 *   carriage return and tab are allowed, since they separate lines and columns in
 *   real back fields and cannot reorder glyphs. A clean link beside a bidi control is dropped too:
 *   an author who plants one has shown intent to deceive, and the context the user
 *   reads is no longer trustworthy.
 * - Match level ([containsRenderingHazard]): every match is also rejected if it
 *   contains ANY Cf or Cc codepoint (line feed, carriage return and tab included) or
 *   a line or paragraph separator (U+2028 / U+2029). It is load-bearing for Cf and
 *   most Cc: the URL pattern admits them, so this check is what keeps them out of link
 *   targets. No pattern can match whitespace that breaks a line, so for line feed,
 *   carriage return, tab and U+2028 / U+2029 it is a backstop.
 *
 * Every pattern spells out its character classes instead of `\s` / `\d`, which are
 * Unicode-aware on Android (ICU) but ASCII-only on the JVM, so unit tests describe
 * device behaviour.
 *
 * Public so consumers that render their own back-field layouts (e.g. walt-android's
 * flat-row PKPASS detail screen) can invoke the canonical scanner instead of
 * hand-rolling regex. [LinkSpan]'s constructor is `internal`, so the only way a
 * consumer obtains a span is through [scan] — every span they see has therefore
 * passed the bidi-spoofing rejection, the URL/phone/email shape checks, and the
 * containment rules below. `copy(...)` on a scanner-produced span still works for
 * styling needs.
 */
public object FieldLinkScanner {

    // Stops only at Unicode White_Space or `<>"'()`, so a URL with an embedded bidi
    // control matches whole and is rejected rather than truncated to a safe prefix.
    private val urlRegex = Regex(
        """https?://[^\t\n\u000B\f\r \u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000<>"'()]+""",
    )
    private val emailRegex = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

    // ASCII digits only, so digits that can be reordered on screen never link. The only
    // whitespace admitted is horizontal (U+0020, U+00A0, U+2007, U+2009, U+202F).
    private val phoneRegex =
        Regex("""(?<![0-9])(\+?[0-9][0-9 \u00A0\u2007\u2009\u202F\-()]{6,}[0-9])(?![0-9])""")

    public fun scan(fieldValue: String, source: SourceField): List<LinkSpan> {
        if (containsFieldHazard(fieldValue)) return emptyList()

        val spans = mutableListOf<LinkSpan>()

        for (match in cleanMatches(urlRegex, fieldValue)) {
            spans += LinkSpan(
                start = match.range.first,
                endExclusive = match.range.last + 1,
                intent = B3UrlIntent(
                    url = match.value,
                    sourceField = source,
                    registrableDomain = registrableDomainOf(match.value),
                ),
            )
        }

        for (match in cleanMatches(emailRegex, fieldValue)) {
            // Skip emails inside an already-claimed URL span (avoid double-marking
            // "http://x@y.com").
            if (overlapsExisting(match.range.first, match.range.last + 1, spans)) continue
            spans += LinkSpan(
                start = match.range.first,
                endExclusive = match.range.last + 1,
                intent = EmailIntent(emailAddress = match.value, sourceField = source),
            )
        }

        for (match in cleanMatches(phoneRegex, fieldValue)) {
            val digitCount = match.value.count { it.isDigit() }
            if (digitCount < 7) continue
            if (!hasPhoneFormattingHint(match, fieldValue)) continue
            if (overlapsExisting(match.range.first, match.range.last + 1, spans)) continue
            spans += LinkSpan(
                start = match.range.first,
                endExclusive = match.range.last + 1,
                intent = PhoneIntent(phoneNumber = match.value.trim(), sourceField = source),
            )
        }

        return spans.sortedBy { it.start }
    }

    private fun cleanMatches(regex: Regex, fieldValue: String): Sequence<MatchResult> =
        regex.findAll(fieldValue).filterNot { containsRenderingHazard(it.value) }

    /**
     * True if [s] contains any Unicode Cf (Format) or Cc (Control) codepoint, or a line
     * or paragraph separator (U+2028 / U+2029). Cf and Cc include bidi controls
     * (U+202A-U+202E, U+2066-U+2069, U+200E/U+200F, U+061C), zero-width characters
     * (U+200B-U+200D, U+FEFF), supplementary-plane format characters such as TAG
     * characters (U+E0001, U+E0020-U+E007F), and raw control bytes (U+0000-U+001F,
     * U+007F-U+009F). All of them can change the rendered glyph order, visibility or
     * line layout of a string without changing what it resolves to, breaking the
     * "displayed string equals actionable string" trust claim.
     *
     * Iterates code points, not UTF-16 chars, so a surrogate pair is classified as the
     * character it encodes. Applied to every matched link substring.
     */
    internal fun containsRenderingHazard(s: String): Boolean = s.codePoints().anyMatch { cp ->
        isFormatOrControl(cp) || Character.getType(cp) in SEPARATOR_TYPES
    }

    /**
     * Whole-field gate: any Cf or Cc codepoint except line feed, carriage return and tab.
     * U+2028 / U+2029 are not Cf or Cc and, like line feed, break lines without reordering.
     */
    internal fun containsFieldHazard(s: String): Boolean = s.codePoints().anyMatch { cp ->
        cp !in FIELD_WHITESPACE && isFormatOrControl(cp)
    }

    private fun isFormatOrControl(cp: Int): Boolean =
        Character.getType(cp) == Character.FORMAT.toInt() || Character.isISOControl(cp)

    private val FIELD_WHITESPACE = setOf('\n'.code, '\r'.code, '\t'.code)

    private val SEPARATOR_TYPES = setOf(
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
    )

    /**
     * True if the matched phone candidate carries at least one visible formatting hint:
     * an internal `+`, dash, space, or parenthesis, OR an immediately adjacent `+` /
     * `(` before the match or `)` after it. A bare digit run with no formatting and no
     * adjacent paren or `+` is rejected.
     *
     * The two-stage check (internal + adjacent) handles the regex quirk that the inner
     * pattern starts with `\+?\d`, so a phone written as `(5551234567)` matches only
     * the digits — the parens stay outside the match. Without an adjacency check those
     * inputs would lose their hints and be rejected. The space character is *not*
     * accepted as an adjacency hint: surrounding spaces are the default text rhythm and
     * would re-admit every numeric reference value (the wpass-536 regression).
     *
     * The check matches Apple's data-detector posture: numeric strings without
     * formatting hints (ticket numbers, order IDs, barcodes, member numbers) are left
     * non-tappable. This is the wpass-536 regression guard — the prior implementation
     * fired on any 7+-digit run and turned every reference-number field into an
     * accidental "Call this number?" prompt. It is also a meaningful narrowing of the
     * attack surface: a malicious pass author can no longer plant a premium-rate dial
     * string in a field labeled `ticketNumber` and rely on auto-detection to surface it.
     */
    private fun hasPhoneFormattingHint(match: MatchResult, fullText: String): Boolean {
        if (match.value.any { it in INTERNAL_PHONE_HINTS }) return true
        val before = fullText.getOrNull(match.range.first - 1)
        val after = fullText.getOrNull(match.range.last + 1)
        return before in PHONE_PREFIX_HINTS || after == ')'
    }

    private fun overlapsExisting(
        start: Int,
        endExclusive: Int,
        existing: List<LinkSpan>,
    ): Boolean = existing.any { it.start < endExclusive && start < it.endExclusive }

    // A phone match starts and ends on an ASCII digit, so surrounding parens are found by
    // adjacency. No-break spaces are not hints: such a number without `+` stays unlinked.
    private val INTERNAL_PHONE_HINTS = setOf('+', '-', ' ')

    /** `+` and `(` immediately before a digit run signal an unmatched-paren or international form. */
    private val PHONE_PREFIX_HINTS = setOf('+', '(')

    /**
     * Best-effort, PSL-free registrable-domain extraction for a scanned URL. The
     * result is a presentation aid for `B3UrlConfirmSheet`'s domain-hero layout —
     * the verbatim URL stays on the forensic row and carries the trust contract.
     *
     * Why no Public Suffix List: it would add ~200 KB and a monthly-freshness
     * obligation for a presentation aid. The PSL-free answer for `example.co.uk`
     * is `example.co.uk` (one label deeper than the PSL answer). Over-disclosing
     * the destination beats under-disclosing it.
     *
     * Transformation: strip scheme, take the authority before the first `/?#`,
     * drop `userinfo@` and `:port`, lowercase, trim trailing `.`. If the host has
     * three or more labels and the leading label is `www` / `m` / `mb` / `mobile`,
     * drop it; otherwise return the host unchanged. The `>= 3 labels` floor
     * prevents collapsing two-label hosts like `m.com` → `com`, which would
     * mislabel the destination rather than just over-disclose it.
     *
     * Consumers MUST NOT route the return value into an outbound `Intent` — it
     * carries no scheme, no path, and is structurally lossy. The verbatim URL on
     * [B3UrlIntent.url] is the only string the host's `Intent.ACTION_VIEW`
     * receives.
     */
    @Suppress("ReturnCount")
    internal fun registrableDomainOf(url: String): String? {
        val schemeStripped = url.removePrefix("https://").removePrefix("http://")
        if (schemeStripped == url) return null
        val authorityEnd = schemeStripped.indexOfAny(charArrayOf('/', '?', '#'))
        val authority = if (authorityEnd < 0) schemeStripped else schemeStripped.substring(0, authorityEnd)
        if (authority.isEmpty()) return null
        val afterUserinfo = authority.substringAfterLast('@')
        // IPv6 literals appear bracketed; if so, return the bracketed form so the
        // hero still shows what `Uri.parse` would call the host.
        val hostAndPort = if (afterUserinfo.startsWith("[")) {
            val close = afterUserinfo.indexOf(']')
            if (close < 0) return null
            afterUserinfo.substring(0, close + 1)
        } else {
            afterUserinfo.substringBefore(':')
        }
        val host = hostAndPort.lowercase().trimEnd('.')
        if (host.isEmpty()) return null
        val labels = host.split('.')
        // Only strip a mirror label when there's a non-TLD label behind it.
        // `m.com` (2 labels) MUST stay as `m.com`, not collapse to `com`.
        if (labels.size < 3) return host
        val first = labels.first()
        return if (first in MIRROR_LABELS) labels.drop(1).joinToString(".") else host
    }

    private val MIRROR_LABELS = setOf("www", "m", "mb", "mobile")
}

/**
 * One detected link in a back-field value. [start] and [endExclusive] are UTF-16
 * char offsets (the units Kotlin's `Regex`, `String.substring`, and
 * `AnnotatedString` all use) into the field string the consumer passed to
 * [FieldLinkScanner.scan]; the consumer should use them to style and route taps
 * over that range. [intent] carries the verbatim substring as its target — no
 * normalization, no scheme injection — so a confirmation sheet built from it
 * shows the user exactly what will leave the device.
 *
 * The constructor is `internal`: consumers can only obtain a `LinkSpan` from
 * [FieldLinkScanner.scan], which guarantees the intent target survived the
 * scanner's validation rules.
 */
public data class LinkSpan internal constructor(
    public val start: Int,
    public val endExclusive: Int,
    public val intent: SecurityIntent,
)
