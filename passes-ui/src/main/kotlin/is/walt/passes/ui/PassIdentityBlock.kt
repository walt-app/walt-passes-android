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

/**
 * The canonical Compose renderer of a pkpass's visible identity (ADR 0007 D6). Given
 * the parsed [pass] and an optional [userLabel] override, this composable renders:
 *
 * - [userLabel] == `null` (or trimmed-empty): the substituted signed
 *   `organizationName` alone, matching the pre-override eyebrow shape PassFront has
 *   always rendered.
 * - [userLabel] non-null and distinct from the substituted `organizationName`: the
 *   [userLabel] as the primary line and the signed `organizationName` as a sub-line
 *   eyebrow underneath. Both on the same surface, both legible (the trust-caption
 *   rule).
 * - [userLabel] non-null and equal to the substituted `organizationName` (case-
 *   insensitive ASCII compare after trim): the signed `organizationName` alone. The
 *   eyebrow is suppressed because the primary identity already IS the signed
 *   identity; the trust rule is satisfied trivially.
 *
 * Every Compose surface that presents a pkpass's primary visible identity SHOULD
 * route through this composable when a user-label override may be set. Consumers
 * whose row shape is incompatible with a vertically stacked Column (single-line
 * title + single-line subtitle in a fixed-layout list row, for example) MAY instead
 * call [resolvePassDisplayIdentity] directly to obtain the same FSI/PDI-fenced
 * `(primary, eyebrow)` pair and feed it into their own typography; that is the
 * only kernel-blessed bypass. Inlining the comparison, trim, or fence on the
 * consumer side is a trust-claim violation (ADR 0007 D5).
 *
 * Both lines are fenced with FSI/PDI via [resolvePassDisplayIdentity] — both the
 * user-supplied `userLabel` and the parsed-from-PKPASS `organizationName` are
 * untrusted strings that could carry bidi-override characters. The fence prevents
 * either line from reordering the other or surrounding chrome. Mirrors
 * `ScannableCardTile` and `passes-pdf-ui::DocumentTile` for the analogous
 * display-label fields.
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
    // Key on the fields the resolver actually reads. `Pass`'s data-class equals walks
    // every ImageBytes via contentEquals, which is measurable on multi-MB strip/thumb
    // images during recomposition; substituting the load-bearing inputs keeps the
    // remember scope correct without paying that cost.
    val identity = remember(pass.organizationName, pass.locales, userLabel, locale) {
        resolvePassDisplayIdentity(pass, userLabel, locale)
    }

    val resolvedPrimary = if (primaryColor == Color.Unspecified) LocalContentColor.current else primaryColor
    val resolvedEyebrow = when {
        eyebrowColor != Color.Unspecified -> eyebrowColor
        else -> resolvedPrimary.copy(alpha = 0.7f)
    }

    if (identity.eyebrow == null) {
        Text(
            text = identity.primary,
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
            text = identity.primary,
            style = MaterialTheme.typography.labelLarge,
            color = resolvedPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Eyebrow (signed identity): never truncate; mirrors ScannableCardTrustCaption's
        // "trust caption never truncates" posture so the issuer identity stays visible
        // even under bounded tile chrome.
        Text(
            text = identity.eyebrow,
            style = MaterialTheme.typography.labelSmall,
            color = resolvedEyebrow,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}
