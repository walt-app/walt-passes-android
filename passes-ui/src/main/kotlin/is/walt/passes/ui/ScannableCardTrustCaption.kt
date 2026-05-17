package `is`.walt.passes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `is`.walt.passes.ui.internal.PencilIcon
import `is`.walt.passes.ui.theme.LocalPassesSemantics
import `is`.walt.passes.ui.theme.toComposeColor

/**
 * The non-suppressible "this is something you typed, not a verified pass" caption that
 * anchors the trust contract of every `ScannableCard` surface (C2 in
 * `docs/SCANNABLE_CARD_THREAT_MODEL.md`): a card rendered by Walt is never
 * signature-verified, has no issuer, and is presented under a fixed caption that the
 * user cannot dismiss and the host cannot hide.
 *
 * The composable has no `enabled` parameter, no theme token that hides it, and no
 * `ScannableCardTile` / `ScannableCardScreen` overload that skips rendering it. Mirrors
 * `DocumentTrustCaption` and `ExpiredOverlay`: the trust claim is structural, not a
 * configuration. Adding a parameter to this function or a sibling overload fails
 * `ComposableSurfaceLockTest`.
 *
 * Layout is a flat [Row] of the pencil glyph followed by the caption text, both
 * center-aligned. The caption uses a semi-bold weight so it survives glance-level
 * viewing even on small tiles; [softWrap] is false and overflow is [TextOverflow.Visible]
 * so the caption never truncates at any tile size (the constraint comes from the issue
 * acceptance criteria: visible at every tile size, does not truncate).
 *
 * The displayed text is a fixed English literal; no part of it comes from the card.
 * The user-controlled `label` is rendered separately by `ScannableCardTile` and is
 * wrapped in FSI/PDI by `passes-ui-core::isolated` at that boundary.
 */
@Composable
public fun ScannableCardTrustCaption(
    modifier: Modifier = Modifier,
) {
    val style = LocalPassesSemantics.current.unverifiedArtifact
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(style.captionBackground.toComposeColor())
            .padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = PencilIcon,
            // Decorative: the verbatim caption text sits immediately beside it and is
            // the audit-relevant semantics node.
            contentDescription = null,
            tint = style.captionIconTint.toComposeColor(),
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = TRUST_CAPTION_TEXT,
            // Semi-bold so the caption survives glance-level viewing even when the
            // surrounding tile chrome uses a lighter weight (issue acceptance criterion:
            // typographic weight survives glance-level viewing).
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = style.captionForeground.toComposeColor(),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}

/**
 * The exact caption copy. Wording is the load-bearing part of
 * `SCANNABLE_CARD_THREAT_MODEL.md` C2; a contributor changing this string is making a
 * security-policy edit and the test suite — which asserts the literal — will require
 * them to update the assertion.
 *
 * Note on dual-anchor placement: the caption is composed BOTH inside `ScannableCardTile`
 * (so the wallet-list user sees it before tapping) AND inside `ScannableCardScreen` (so
 * a deep-linked or shortcut-launched scan surface does not bypass it). The duplication
 * is deliberate; do NOT refactor it to a single render site.
 */
private const val TRUST_CAPTION_TEXT: String = "Created by you"
