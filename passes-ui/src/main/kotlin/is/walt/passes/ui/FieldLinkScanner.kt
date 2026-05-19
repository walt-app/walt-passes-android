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
 * - The URL character class is RFC 3986's reserved + unreserved + percent-encoded ASCII
 *   only. Unicode characters in URLs MUST be percent-encoded (`%E2%80%AE` for U+202E,
 *   `xn--` Punycode for IDN hosts) to be detected; raw bidi / format / control
 *   characters cause the URL to be rejected outright. This defends against the bidi
 *   spoofing class — e.g. `https://attacker.example/‮gpj.elgoog//:sptth` rendering
 *   visually as a Google asset URL while `Uri.parse` resolves to `attacker.example`.
 *   Tested in `FieldLinkScannerTest`.
 * - A phone number requires at least seven digits AND at least one formatting hint
 *   (a leading `+`, an embedded space, dash, or parenthesis) before it is recognised.
 *   Bare digit runs — ticket numbers, order IDs, member numbers, barcode payloads —
 *   are left as plain text. This mirrors Apple's data-detector behavior in iOS Wallet
 *   and prevents two failure modes: (a) accidental "Call this number?" prompts on
 *   every numeric field of a real pass, and (b) a malicious pass embedding a
 *   premium-rate dial string in a value that the user reads as a reference number.
 * - An email requires an `@` with non-empty ASCII local and domain parts.
 *
 * Belt-and-suspenders: every match is post-filtered through [containsRenderingHazard],
 * which discards any match containing a Unicode formatting (Cf) or control (Cc)
 * codepoint. This is redundant for phone and email under the current regexes but
 * locks the property so a future regex relaxation does not silently re-open a
 * spoofing path.
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

    // Permissive on the boundary chars (whitespace + brackets + quotes), then the
    // post-filter (`containsRenderingHazard`) rejects matches containing Cf/Cc
    // characters. The two-stage approach matters: a hostile URL containing
    // U+202E (Right-to-Left Override) would partial-match under a tighter regex
    // (stopping at the override and accepting the safe prefix), leaving a partial
    // URL detected. Capturing the full string here lets the filter discard the
    // entire match, so the back-field surfaces no link at all rather than a
    // truncated one whose display is no longer the hostile reorder yet still
    // differs from what the field text shows.
    private val urlRegex = Regex("""https?://[^\s<>"'()]+""")
    private val emailRegex = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

    // Phone: starts with + or with a digit; digits, spaces, dashes, parentheses; total
    // digit count >= 7. Anchored on word boundaries so a serial number like "12345678"
    // adjacent to other text does not accidentally match.
    private val phoneRegex =
        Regex("""(?<!\d)(\+?\d[\d\s\-()]{6,}\d)(?!\d)""")

    public fun scan(fieldValue: String, source: SourceField): List<LinkSpan> {
        // Field-level rejection: if ANY part of this field contains a Unicode
        // formatting (Cf) or control (Cc) codepoint, surface NO tappable links from
        // it. Even a clean URL adjacent to a hostile one becomes non-tappable; the
        // user must copy by hand.
        //
        // This is broader than per-match rejection but the right posture for the
        // trust model: an untrusted pass author who can plant ANY rendering hazard
        // in a field has demonstrated intent to deceive, and any other "link" in
        // that field is suspect by association — even if its match substring is
        // ASCII-clean, the visible context the user reads in the back-field surface
        // is not. Cutting the entire field off the auto-link path forces a manual
        // intent (long-press to copy, paste into a browser) where the user has to
        // see the verbatim string outside any directional reordering.
        if (containsRenderingHazard(fieldValue)) return emptyList()

        val spans = mutableListOf<LinkSpan>()

        for (match in urlRegex.findAll(fieldValue)) {
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

        for (match in emailRegex.findAll(fieldValue)) {
            // Skip emails inside an already-claimed URL span (avoid double-marking
            // "http://x@y.com").
            if (overlapsExisting(match.range.first, match.range.last + 1, spans)) continue
            spans += LinkSpan(
                start = match.range.first,
                endExclusive = match.range.last + 1,
                intent = EmailIntent(emailAddress = match.value, sourceField = source),
            )
        }

        for (match in phoneRegex.findAll(fieldValue)) {
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

    /**
     * True if [s] contains any Unicode Cf (Format) or Cc (Control) codepoint. These
     * include bidi controls (U+202A-U+202E, U+2066-U+2069, U+200E/U+200F, U+061C),
     * zero-width characters (U+200B-U+200D, U+FEFF), and raw control bytes (U+0000-U+001F,
     * U+007F-U+009F). All of them can change the rendered glyph order or visibility of
     * a string without changing its byte content, breaking the "displayed string equals
     * actionable string" trust claim.
     *
     * The check is character-by-character, O(n), and runs only against already-matched
     * link substrings so the cost is bounded by the number of links per pass.
     */
    internal fun containsRenderingHazard(s: String): Boolean = s.any { c ->
        c.category == CharCategory.FORMAT || c.isISOControl()
    }

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

    // The phone regex `(?<!\d)(\+?\d[\d\s\-()]{6,}\d)(?!\d)` cannot start with `(` or
    // end with `)` (its outer anchors require digits at both edges), so any paren
    // surrounding a phone number lands *adjacent to* the match, not inside it. The
    // internal-hint set therefore lists only the characters that can actually appear
    // inside `match.value` — `+`, `-`, and space. Adjacent parens are picked up via
    // [PHONE_PREFIX_HINTS] / the trailing-`)` check in [hasPhoneFormattingHint].
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
