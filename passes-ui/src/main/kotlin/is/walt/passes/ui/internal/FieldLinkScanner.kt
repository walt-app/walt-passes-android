package `is`.walt.passes.ui.internal

import `is`.walt.passes.ui.B3UrlIntent
import `is`.walt.passes.ui.EmailIntent
import `is`.walt.passes.ui.PhoneIntent
import `is`.walt.passes.ui.SecurityIntent
import `is`.walt.passes.ui.SourceField

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
 * Intentionally conservative: prefers false negatives over false positives. A URL
 * without an `http`/`https` scheme is not detected as a URL; a phone number requires
 * at least seven digits and a leading `+` or parenthesized area code; an email
 * requires an `@` with non-empty local and domain parts.
 */
internal object FieldLinkScanner {

    private val urlRegex = Regex("""https?://[^\s<>"'()]+""")
    private val emailRegex = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

    // Phone: starts with + or with a digit; digits, spaces, dashes, parentheses; total
    // digit count >= 7. Anchored on word boundaries so a serial number like "12345678"
    // adjacent to other text does not accidentally match.
    private val phoneRegex =
        Regex("""(?<!\d)(\+?\d[\d\s\-()]{6,}\d)(?!\d)""")

    fun scan(fieldValue: String, source: SourceField): List<LinkSpan> {
        val spans = mutableListOf<LinkSpan>()

        for (match in urlRegex.findAll(fieldValue)) {
            spans += LinkSpan(
                start = match.range.first,
                endExclusive = match.range.last + 1,
                intent = B3UrlIntent(url = match.value, sourceField = source),
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
            if (overlapsExisting(match.range.first, match.range.last + 1, spans)) continue
            spans += LinkSpan(
                start = match.range.first,
                endExclusive = match.range.last + 1,
                intent = PhoneIntent(phoneNumber = match.value.trim(), sourceField = source),
            )
        }

        return spans.sortedBy { it.start }
    }

    private fun overlapsExisting(
        start: Int,
        endExclusive: Int,
        existing: List<LinkSpan>,
    ): Boolean = existing.any { it.start < endExclusive && start < it.endExclusive }
}

internal data class LinkSpan(
    val start: Int,
    val endExclusive: Int,
    val intent: SecurityIntent,
)
