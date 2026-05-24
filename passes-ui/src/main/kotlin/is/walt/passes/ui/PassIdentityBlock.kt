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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassLocale
import `is`.walt.passes.core.lookupOrSelf
import `is`.walt.passes.core.resolveLocalizedStrings
import `is`.walt.passes.ui.core.isolated

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
 * Both lines are fenced with FSI/PDI via [isolated] — both the user-supplied
 * `userLabel` and the parsed-from-PKPASS `organizationName` are untrusted strings
 * that could carry bidi-override characters. The fence prevents either line from
 * reordering the other or surrounding chrome. Mirrors `ScannableCardTile` and
 * `passes-pdf-ui::DocumentTile` for the analogous display-label fields.
 */
@Suppress("LongParameterList") // detekt functionThreshold=6; trips on six declared params.
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

    val override = userLabel
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals(displayOrganizationName.trim(), ignoreCase = true) }

    if (override == null) {
        Text(
            text = isolated(displayOrganizationName),
            style = MaterialTheme.typography.labelLarge,
            color = resolvedPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Primary line (user-supplied): cap at 2 lines, ellipsize. Mirrors
        // ScannableCardTile / DocumentTile for analogous user-controlled labels.
        Text(
            text = isolated(override),
            style = MaterialTheme.typography.labelLarge,
            color = resolvedPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Eyebrow (signed identity): never truncate; mirrors ScannableCardTrustCaption's
        // "trust caption never truncates" posture so the issuer identity stays visible
        // even under bounded tile chrome.
        Text(
            text = isolated(displayOrganizationName),
            style = MaterialTheme.typography.labelSmall,
            color = resolvedEyebrow,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}
