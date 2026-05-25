package `is`.walt.passes.ui

import `is`.walt.passes.core.LocalizedStrings
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
 * Computes the resolved visible identity from a raw [organizationName] + optional
 * [userLabel] override + an optional pre-resolved [localizedStrings] table, per
 * ADR 0007 D5 / D6. The Summary-shaped overload: list-row consumers that hold a
 * `PassSummary` (and therefore do NOT carry the pass's `locales` map, since the
 * projection is explicitly designed to avoid locale I/O) call this directly with
 * `LocalizedStrings.Empty`, which makes the substitution a pure pass-through and
 * fences the raw `organizationName` verbatim. Detail-view consumers that DO hold
 * a resolved strings table can pass it in; the [Pass]-bearing overload below
 * delegates here after running the locale chain.
 *
 * Rules, in order:
 *  1. Substitute [organizationName] through [localizedStrings] (Apple's documented
 *     `.lproj/pass.strings` lookup; misses fall through to the raw value, and
 *     [LocalizedStrings.Empty] makes every lookup a miss).
 *  2. Trim [userLabel]; treat empty as no-override.
 *  3. Case-insensitive ASCII compare the trimmed override against the trimmed
 *     substituted `organizationName`; equality suppresses the override.
 *  4. FSI/PDI-fence both surviving lines via [isolated].
 *
 * This is the primitive form of the resolver and the single source of truth for
 * the trust-caption rule.
 */
public fun resolvePassDisplayIdentity(
    organizationName: String,
    userLabel: String?,
    localizedStrings: LocalizedStrings = LocalizedStrings.Empty,
): PassDisplayIdentity {
    val displayOrganizationName = localizedStrings.lookupOrSelf(organizationName)

    val override = userLabel
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals(displayOrganizationName.trim(), ignoreCase = true) }

    return if (override == null) {
        PassDisplayIdentity(primary = isolated(displayOrganizationName), eyebrow = null)
    } else {
        PassDisplayIdentity(primary = isolated(override), eyebrow = isolated(displayOrganizationName))
    }
}

/**
 * The [Pass]-bearing convenience overload. Resolves the pass's strings table for
 * [locale] via Apple's documented locale-fallback chain (see
 * [Pass.resolveLocalizedStrings]) and delegates to the Summary-shaped primitive
 * above. Detail-view callers holding a full [Pass] should prefer this overload;
 * list-row callers holding only a `PassSummary` should call the primitive
 * directly with `LocalizedStrings.Empty`.
 */
public fun resolvePassDisplayIdentity(
    pass: Pass,
    userLabel: String?,
    locale: PassLocale = PassLocale("en"),
): PassDisplayIdentity = resolvePassDisplayIdentity(
    organizationName = pass.organizationName,
    userLabel = userLabel,
    localizedStrings = pass.resolveLocalizedStrings(locale),
)
