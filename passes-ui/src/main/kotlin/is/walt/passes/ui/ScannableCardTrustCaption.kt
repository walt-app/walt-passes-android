package `is`.walt.passes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
 * Layout is a flat [Row] holding the single caption [Text], its `captionBackground`
 * clipped into an inset rounded chip (wpass-v3u): the chip is inset from the surface
 * edges and rounded so the caption reads as an intentional element rather than an
 * edge-to-edge band. The leading pencil glyph was removed in the same restyle — the
 * caption is text-only. The caption uses a semi-bold weight so it survives glance-level
 * viewing even on small tiles; [softWrap] is false and overflow is [TextOverflow.Visible]
 * so the caption never truncates at any tile size (the constraint comes from the issue
 * acceptance criteria: visible at every tile size, does not truncate). This is a RESTYLE
 * only — same render sites, byte-for-byte wording, still no way to suppress it.
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
            .padding(horizontal = CHIP_INSET)
            .clip(RoundedCornerShape(CHIP_RADIUS))
            .background(style.captionBackground.toComposeColor())
            .padding(PaddingValues(horizontal = 12.dp, vertical = 10.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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

/** Inset rounded-chip geometry for the caption (wpass-v3u). Kernel-owned, not themable. */
private val CHIP_INSET = 12.dp
private val CHIP_RADIUS = 10.dp

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
