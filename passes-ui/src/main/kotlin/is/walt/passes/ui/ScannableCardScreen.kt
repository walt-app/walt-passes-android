package `is`.walt.passes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.ui.core.isolated

/**
 * Full-screen surface for scanning a [ScannableCard]. A content-sized card face holds a
 * literal-white code panel, the user-controlled label and the payload readback (all
 * FSI/PDI isolated), with the [ScannableCardTrustCaption] docked at the bottom by
 * default (omitted only under the audited [TrustCaptionPlacement.HostedTypeRow]
 * concession — see [trustCaption]).
 *
 * The card is sized to the code (plus a quiet-zone margin), not to the whole screen
 * (wpass-1wu.2 / Walt wlt-n5z): the rest of the surface is transparent so the host's
 * background shows through instead of a full wash in a tall slot. ZXing bakes the scan
 * quiet zone into the matrix (`ZxingBarcodeEncoder`), so scannability is preserved;
 * [CODE_QUIET_ZONE] adds visual breathing room inside the panel and the QR/1D
 * `ContentScale` split in [ScannableCardView] is unchanged.
 *
 * ## Face tint vs. code panel
 *
 * [faceTint] colors the card face only. The panel directly behind the code is
 * [SCAN_CODE_PANEL] — literally white in both themes, never a theme token and never the
 * tint — because the code is real content and must stay theme-independent and scannable
 * in dark mode. This mirrors `CompactCodeView`'s `COMPACT_CODE_BACKING` guarantee, so
 * both the list-face and detail-face renders of a code share one white-backing rule.
 * Routing the panel off that constant, or letting the tint reach it, is amending the
 * contract rather than refactoring it.
 *
 * The tint is presentation only. The kernel never learns why a color was chosen and
 * stores nothing: which color an item carries is the consumer's (walt-android's
 * `WalletColorRepository`, keyed per wallet entry). `ScannableCard` deliberately carries
 * no color field — wpass-q5p removed it and it stays removed. Label and payload ink is
 * derived from the tint's luminance so an arbitrary consumer tint stays legible in both
 * themes; that is a contrast guarantee, not a brand token.
 *
 * Trust contract: by default ([TrustCaptionPlacement.Docked]) the caption is composed at
 * the bottom of the screen (C2 in `docs/SCANNABLE_CARD_THREAT_MODEL.md`), structurally
 * separate from any host navigation chrome. No theme token and no overload can drop it;
 * the ONE way it is omitted is the audited [TrustCaptionPlacement.HostedTypeRow]
 * concession below, under which the host carries provenance via its own "Pass type" row
 * (C2 "Pass type" row concession). Neither [showLabel] nor [faceTint] can suppress the
 * barcode, the payload caption, or the trust caption.
 *
 * [trustCaption] selects how the provenance signal is carried: with
 * [TrustCaptionPlacement.HostedTypeRow] the kernel renders no caption here because the
 * host carries the claim itself, as a "Pass type" row inside its own details section
 * (value "Scanned" for a scannable card). Under that mode a neutral type label is an
 * accepted carrier and the row may sit in a collapsed-by-default foldout — see
 * `TrustCaptionPlacement` and the C2 "Pass type" row concession in the threat model.
 * Defaults to [TrustCaptionPlacement.Docked] (the verbatim docked caption), so every
 * existing caller is unchanged.
 *
 * No share / save-to-photos / print affordance, and no overflow menu. The user came
 * here to scan, then back out — those are the only two paths off this surface. Host
 * navigation chrome (back button, screen title) is supplied by the consumer's
 * scaffold; this composable is the body only.
 *
 * @param showLabel when false, the built-in label is not rendered. Defaults to true so
 *   every existing caller is unchanged. Hosts that render their own title above this
 *   surface (e.g. an editable self-title) pass false to avoid a duplicate (Walt wlt-tct).
 * @param trustCaption how the provenance signal is carried. Defaults to
 *   [TrustCaptionPlacement.Docked] (verbatim docked caption).
 *   [TrustCaptionPlacement.HostedTypeRow] drops the kernel caption so the host carries
 *   provenance via its own "Pass type" details row under the C2 concession (wpass-gv6).
 * @param faceTint color of the card face behind the code panel. Defaults to
 *   [Color.Unspecified], which keeps the `MaterialTheme.colorScheme.surface` face every
 *   existing caller gets today (wpass-80y.1 / Walt wlt-38v8).
 */
@Composable
public fun ScannableCardScreen(
    card: ScannableCard,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    trustCaption: TrustCaptionPlacement = TrustCaptionPlacement.Docked,
    faceTint: Color = Color.Unspecified,
) {
    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            CodeCard(card = card, showLabel = showLabel, faceTint = faceTint)
        }

        // Docked: the kernel renders the verbatim caption here. HostedTypeRow: the kernel
        // renders nothing — the host carries provenance via its own "Pass type" details
        // row (wpass-gv6 / C2 concession). Exhaustive `when` so a future placement arm
        // must make an explicit decision rather than silently dropping the caption.
        when (trustCaption) {
            TrustCaptionPlacement.Docked ->
                ScannableCardTrustCaption(modifier = Modifier.fillMaxWidth())
            TrustCaptionPlacement.HostedTypeRow -> Unit
        }
    }
}

/**
 * The content-sized card: tinted face, literal-white code panel, then the label and
 * payload readback on the face. [faceTint] never reaches [SCAN_CODE_PANEL].
 */
@Composable
private fun CodeCard(
    card: ScannableCard,
    showLabel: Boolean,
    faceTint: Color,
) {
    val tinted = faceTint.isSpecified
    val face = if (tinted) faceTint else MaterialTheme.colorScheme.surface
    val ink = if (tinted) inkOn(faceTint) else MaterialTheme.colorScheme.onSurface
    val metaInk =
        if (tinted) ink.copy(alpha = META_INK_ALPHA) else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        color = face,
        shape = RoundedCornerShape(CARD_RADIUS),
    ) {
        Column(
            modifier = Modifier.padding(CARD_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PANEL_TO_TEXT_GAP),
        ) {
            Surface(
                color = SCAN_CODE_PANEL,
                shape = RoundedCornerShape(PANEL_RADIUS),
            ) {
                ScannableCardView(
                    card = card,
                    modifier = Modifier.padding(CODE_QUIET_ZONE),
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(LABEL_TO_PAYLOAD_GAP),
            ) {
                if (showLabel) {
                    Text(
                        text = isolated(card.label),
                        style = MaterialTheme.typography.titleMedium
                            .copy(fontWeight = FontWeight.SemiBold),
                        color = ink,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // POS-scan fallback for GH #102; only the detail surface is large enough.
                // Sits on the face, not the code panel, so the panel stays a pure scan
                // target.
                SelectionContainer {
                    Text(
                        text = isolated(card.payload),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = metaInk,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Contrast-derived ink for text sitting on [ScannableCardScreen]'s face. Consumers may
 * pass any tint, so the flip keeps the label and payload legible rather than assuming a
 * light palette. Neutral black/white by design: `passes-ui` carries no brand values.
 */
private fun inkOn(tint: Color): Color =
    if (tint.luminance() > INK_FLIP_LUMINANCE) Color.Black else Color.White

/** Panel padding around the code. The scan quiet zone is in the matrix; this is visual. */
private val CODE_QUIET_ZONE = 16.dp

private val CARD_RADIUS = 20.dp
private val CARD_PADDING = 18.dp
private val PANEL_RADIUS = 16.dp
private val PANEL_TO_TEXT_GAP = 16.dp
private val LABEL_TO_PAYLOAD_GAP = 4.dp

private const val INK_FLIP_LUMINANCE = 0.5f
private const val META_INK_ALPHA = 0.6f

/**
 * Literally white, never a theme token and never [ScannableCardScreen]'s face tint — the
 * dark-mode scannability guarantee, matching `COMPACT_CODE_BACKING` on the list face.
 * Internal (not private) so the smoke test pins the value; rerouting the panel off this
 * constant is amending the contract, not a refactor.
 */
internal val SCAN_CODE_PANEL: Color = Color.White
