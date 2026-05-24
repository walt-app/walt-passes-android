package `is`.walt.passes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassLocale
import `is`.walt.passes.core.lookupOrSelf
import `is`.walt.passes.core.resolveLocalizedStrings

/**
 * The canonical renderer of a pkpass's visible identity (ADR 0007 D6). Given the parsed
 * [pass] and an optional [userLabel] override, this composable renders:
 *
 * - [userLabel] == `null`: the substituted signed `organizationName` alone, matching the
 *   pre-override eyebrow shape PassFront has always rendered.
 * - [userLabel] non-null and distinct from the substituted `organizationName`: the
 *   [userLabel] as the primary line and the signed `organizationName` as a sub-line
 *   eyebrow underneath. Both on the same surface, both legible (the trust-caption
 *   rule).
 * - [userLabel] non-null and equal to the substituted `organizationName` (case-
 *   insensitive ASCII compare after trim): the signed `organizationName` alone. The
 *   eyebrow is suppressed because the primary identity already IS the signed
 *   identity; the trust rule is satisfied trivially.
 *
 * Every UI surface that presents a pkpass's primary visible identity MUST route
 * through this composable when a user-label override may be set. Bypassing it to
 * render `userLabel` as bare primary identity is a trust-claim violation (ADR 0007
 * D5). Walt-android's pkpass tile, lane row, and detail chrome are expected to call
 * this rather than reading `StoredPass.userLabel` directly.
 *
 * Theming: the kernel chooses the structural shape and typographic hierarchy; the
 * consumer chooses colors via [primaryColor] and [eyebrowColor]. Defaults route
 * through [LocalContentColor] so a host that does not theme falls back to its
 * surrounding `MaterialTheme.contentColor`.
 *
 * @param pass The parsed pass. `pass.organizationName` (after `pass.strings`
 *   substitution against [locale]) is the signed identity surfaced as the eyebrow.
 * @param userLabel The user-supplied override from `StoredPass.userLabel` /
 *   `PassSummary.userLabel`. `null` means no override.
 * @param locale Drives `pass.strings` substitution. Production callers MUST thread the
 *   device locale through; the default is a fallback for tests and previews.
 * @param primaryColor Color of the primary identity line (either the override or the
 *   org name when no override). Defaults to [LocalContentColor].
 * @param eyebrowColor Color of the signed-identity eyebrow underneath an override.
 *   Defaults to [primaryColor] at 70% alpha — a single muted derivation so a host that
 *   does not theme still gets visible hierarchy.
 */
@Suppress("LongParameterList")
@Composable
public fun PassIdentityBlock(
    pass: Pass,
    userLabel: String?,
    modifier: Modifier = Modifier,
    locale: PassLocale = PassLocale("en"),
    primaryColor: Color = Color.Unspecified,
    eyebrowColor: Color = Color.Unspecified,
) {
    val strings = remember(pass.locales, locale) { pass.resolveLocalizedStrings(locale) }
    val displayOrganizationName = strings.lookupOrSelf(pass.organizationName)

    val resolvedPrimary = if (primaryColor == Color.Unspecified) LocalContentColor.current else primaryColor
    val resolvedEyebrow = when {
        eyebrowColor != Color.Unspecified -> eyebrowColor
        else -> resolvedPrimary.copy(alpha = 0.7f)
    }

    val trimmedOverride = userLabel?.trim()?.takeIf { it.isNotEmpty() }
    val shouldRenderOverride = trimmedOverride != null &&
        !trimmedOverride.equals(displayOrganizationName.trim(), ignoreCase = true)

    if (!shouldRenderOverride) {
        Text(
            text = displayOrganizationName,
            style = MaterialTheme.typography.labelLarge,
            color = resolvedPrimary,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = trimmedOverride!!,
            style = MaterialTheme.typography.labelLarge,
            color = resolvedPrimary,
        )
        Text(
            text = displayOrganizationName,
            style = MaterialTheme.typography.labelSmall,
            color = resolvedEyebrow,
        )
    }
}
