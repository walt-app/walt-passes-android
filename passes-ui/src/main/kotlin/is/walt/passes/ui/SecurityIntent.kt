package `is`.walt.passes.ui

/**
 * The three security-confirmation intent families that `passes-ui` mediates between a
 * tapped pass field and the host's outbound action (browser, dialer, mail composer).
 *
 * Each intent carries the *exact string* that will leave the device if the user
 * confirms — never a label, alt text, or massaged copy. The trust claim "the user sees
 * what is going to happen" is enforced by the type itself: there is no field for a
 * display string distinct from the actionable target.
 *
 * walt-android's `feature/passes` host code observes these via the callbacks on
 * `PassBack` (see COMPOSABLE_SIGNATURES.md) and routes them into the corresponding
 * confirmation sheet, which calls `onConfirm` only after the user explicitly accepts.
 */
public sealed interface SecurityIntent {
    public val sourceField: SourceField
}

/**
 * A URL detected in a pass back-field value. The "B3" name in this module's bead
 * predates the work; the rule is: if a pass back-field value parses as a URL, the
 * UI MUST route it through this intent rather than handing it to `Intent.ACTION_VIEW`
 * directly.
 *
 * [registrableDomain] is a best-effort extraction of the host portion suitable for
 * use as a domain-hero label (`tixly.com` for `https://www.tixly.com/refunds`). It
 * is derived structurally — leading `www.` / `m.` / `mobile.` / `mb.` labels are
 * stripped — without consulting a Public Suffix List, so multi-label TLDs like
 * `co.uk` will surface as `example.co.uk`. That is the right answer for the
 * domain-hero treatment in `B3UrlConfirmSheet`: the user wants to see "where am
 * I being sent," and the registrable-domain question for `co.uk` is genuinely
 * one label deeper than `Uri.host` admits without a PSL. A future PSL-backed
 * implementation can tighten this without changing the public type.
 *
 * The full [url] remains the trust-claim-bearing string and is displayed verbatim
 * in the forensic mono row of every confirmation sheet regardless of layout; the
 * registrable domain is a presentation aid, not a substitute. Consumers MUST NOT
 * route this string into any outbound `Intent`.
 *
 * `null` when the host cannot be parsed (e.g. malformed URL the scanner accepted
 * but `Uri.parse` chokes on).
 */
public data class B3UrlIntent(
    public val url: String,
    override val sourceField: SourceField,
    public val registrableDomain: String? = null,
) : SecurityIntent

/**
 * A telephone number detected in a pass back-field value. Routed through a
 * confirmation sheet that displays the verbatim digits the user is about to dial.
 */
public data class PhoneIntent(
    public val phoneNumber: String,
    override val sourceField: SourceField,
) : SecurityIntent

/**
 * An email address detected in a pass back-field value. Routed through a confirmation
 * sheet that displays the verbatim address; the user's mail composer is not
 * pre-populated with subject or body from the pass.
 */
public data class EmailIntent(
    public val emailAddress: String,
    override val sourceField: SourceField,
) : SecurityIntent

/**
 * Where in the pass the security-relevant value originated. Lets the confirmation
 * sheet tell the user "this came from the back field 'support phone'" rather than
 * presenting a number with no provenance.
 *
 * [fieldKey] is the PKPASS field key (`PassField.key`); [fieldLabel] is the
 * already-localized label that the user sees on the pass back; [organizationName]
 * is the issuer's `organizationName` from the pass.
 *
 * All three are sourced from the parsed `Pass`; none are user-supplied.
 */
public data class SourceField(
    public val fieldKey: String,
    public val fieldLabel: String?,
    public val organizationName: String,
)
