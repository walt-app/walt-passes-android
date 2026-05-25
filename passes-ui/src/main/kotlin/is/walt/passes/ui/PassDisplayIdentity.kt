package `is`.walt.passes.ui

import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassLocale
import `is`.walt.passes.core.lookupOrSelf
import `is`.walt.passes.core.resolveLocalizedStrings
import `is`.walt.passes.ui.core.isolated

/**
 * Resolved visible identity for a pkpass, suitable for plugging into any consumer-
 * authored title + subtitle row (and used internally by [PassIdentityBlock]).
 *
 * Both strings are already FSI/PDI-fenced via [isolated]; callers MUST NOT re-wrap
 * them and MUST NOT inline the equality / trim / locale-substitution logic
 * themselves (ADR 0007 D6). Rendering [primary] alone when [eyebrow] is non-null is
 * a trust-claim violation: the signed `organizationName` MUST be displayed on the
 * same surface whenever an override is active (ADR 0007 D5).
 *
 * - [primary]: the fenced primary label. When an override survives the override
 *   rules it is the fenced override; otherwise it is the fenced (localized) signed
 *   `organizationName`.
 * - [eyebrow]: the fenced signed `organizationName`, present only when an override
 *   is active and distinct from the signed identity. `null` means the primary
 *   identity already IS the signed identity and the trust rule is satisfied
 *   trivially.
 */
public data class PassDisplayIdentity(
    public val primary: String,
    public val eyebrow: String?,
)

/**
 * Computes the resolved visible identity for [pass] + an optional [userLabel]
 * override, per ADR 0007 D5 / D6. Pure: safe to call outside any Compose runtime
 * (lifts the canonical trust-caption rule out of [PassIdentityBlock] so a consumer
 * with a fixed row shape can render the strings into its own typography without
 * re-implementing the trust contract).
 *
 * Rules, in order:
 *  1. Substitute [Pass.organizationName] through the pass's strings table for
 *     [locale] (Apple's documented `.lproj/pass.strings` lookup; misses fall
 *     through to the raw value).
 *  2. Trim [userLabel]; treat empty as no-override.
 *  3. Case-insensitive ASCII compare the trimmed override against the trimmed
 *     substituted `organizationName`; equality suppresses the override.
 *  4. FSI/PDI-fence both surviving lines via [isolated].
 *
 * Mirrors the body of [PassIdentityBlock] verbatim; that composable now consumes
 * this resolver so the trust contract has a single source of truth.
 */
public fun resolvePassDisplayIdentity(
    pass: Pass,
    userLabel: String?,
    locale: PassLocale = PassLocale("en"),
): PassDisplayIdentity {
    val strings = pass.resolveLocalizedStrings(locale)
    val displayOrganizationName = strings.lookupOrSelf(pass.organizationName)

    val override = userLabel
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals(displayOrganizationName.trim(), ignoreCase = true) }

    return if (override == null) {
        PassDisplayIdentity(primary = isolated(displayOrganizationName), eyebrow = null)
    } else {
        PassDisplayIdentity(primary = isolated(override), eyebrow = isolated(displayOrganizationName))
    }
}
