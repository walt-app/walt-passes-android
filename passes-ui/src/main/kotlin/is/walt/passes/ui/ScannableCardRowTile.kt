package `is`.walt.passes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.ui.core.isolated
import `is`.walt.passes.ui.theme.LocalPassesSemantics
import `is`.walt.passes.ui.theme.toComposeColor

/**
 * Wallet-row register for a [ScannableCard]. Sibling to [ScannableCardTile]; intended for
 * consumers that interleave scannable cards with passes / PDFs in a single homogeneous
 * list rather than presenting them in their own carousel lane. Parent kernel epic
 * `wpass-hy2`; consumer epic `wlt-6ub` (walt-android wallet-list redesign).
 *
 * Where [ScannableCardTile] carries four redundant artifact-class distinguishers (dashed
 * outline, leading accent band, smaller corner radius, non-suppressible "Created by you"
 * caption) per `docs/SCANNABLE_CARD_THREAT_MODEL.md` C1 / C2, this register intentionally
 * does not. The threat-model concession (recorded in that same doc) permits a homogeneous
 * row when, and only when, all three of:
 *
 *  1. The row carries no signature dot.
 *  2. The row carries no coloured leading band styled to read as a verified-pass band.
 *  3. The detail surface ([ScannableCardScreen]) retains the bottom-docked
 *     non-suppressible [ScannableCardTrustCaption].
 *
 * The trust caption shifts from list-row to detail-surface only. A user who taps the row
 * to use the artifact still sees "Created by you" before scanning. This trade is explicit
 * and bounded; it is not a generalised opt-out of C2.
 *
 * ## Visual shape
 *
 * A flat row, label-led, with a leading neutral-tone accent strip (per-card user colour
 * was removed from the kernel — it would re-create the verified-band read at list scale).
 * The format appears as a short subtitle below the label. No tile, no thumbnail, no badge.
 *
 * The leading strip is sourced from [LocalPassesSemantics] `unverifiedArtifact.accent`,
 * the same accent the carousel tile uses. Consumers wanting a richer leading affordance
 * (format-icon glyph, format-tinted tile) pass a composable via [leadingSlot]; it sits
 * between the accent strip and the label/format column, inside the row's
 * `mergeDescendants` block. The kernel surface stays minimal; the slot is a visual hook
 * only and is not a trust signal — anything composed inside it that exposes a non-null
 * `contentDescription` will participate in the merged description Walt's accessibility
 * contract already pins, so slot content should set `contentDescription = null` on its
 * icons / images and rely on the kernel-built description.
 *
 * ## Trust-claim contract
 *
 * - User-supplied [ScannableCard.label] is wrapped in U+2068 / U+2069 (FSI / PDI) by
 *   `passes-ui-core::isolated`, same defense-in-depth `ScannableCardTile` applies. The
 *   kernel validator already rejects Cf / Cc codepoints at the create boundary (C3);
 *   isolating again ensures a future relaxation of the validator cannot silently weaken
 *   this surface.
 * - The merged [contentDescription] inlines the format token so a TalkBack user hears
 *   "{label}, {format}, barcode card". Setting [contentDescription] on a
 *   `mergeDescendants` node replaces (not appends to) descendant `Text` contributions, so
 *   the format-as-subtitle visible to sighted users would otherwise be silent to AT. The
 *   `rowTileSemanticsExposeFormatToken` smoke test pins that this stays the case.
 * - No signature affordance is composed inside the row. The absence is structural; the
 *   surface-lock test (`scannableCardRowTileHasExactlyFourUserVisibleParameters`) pins
 *   the parameter shape so a future contributor cannot add `showSignatureBadge` without
 *   amending the threat-model concession. [leadingSlot] is the only sanctioned
 *   visual-hook parameter; a signature-bearing addition still belongs out of band.
 *
 * Lifecycle gestures (long-press, overflow, share) and alternative size profiles are
 * consumer responsibilities, same posture as [ScannableCardTile]; the kernel surface
 * stays minimal so the surface-lock test pins a tight shape.
 */
@Composable
public fun ScannableCardRowTile(
    card: ScannableCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingSlot: @Composable () -> Unit = {},
) {
    val accent = LocalPassesSemantics.current.unverifiedArtifact.accent.toComposeColor()
    val labelText = isolated(card.label)
    val formatToken = card.format.rowSubtitle()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                // Setting contentDescription on a merged node replaces descendant Text
                // contributions, so the format subtitle below has to be inlined here or
                // TalkBack loses it.
                contentDescription = "$labelText, $formatToken, barcode card"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeadingAccentStrip(color = accent)
        leadingSlot()
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = TEXT_LEADING_GAP, end = TEXT_TRAILING_PADDING),
            verticalArrangement = Arrangement.spacedBy(TEXT_VERTICAL_GAP),
        ) {
            Text(
                text = labelText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatToken,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LeadingAccentStrip(color: Color) {
    Box(
        modifier = Modifier
            .width(ACCENT_STRIP_WIDTH)
            .fillMaxHeight()
            .background(color),
    )
}

private fun ScannableFormat.rowSubtitle(): String = when (this) {
    ScannableFormat.Code128 -> "Code 128"
    ScannableFormat.Code39 -> "Code 39"
    ScannableFormat.Ean13 -> "EAN-13"
    ScannableFormat.UpcA -> "UPC-A"
    ScannableFormat.Qr -> "QR"
}

private val ROW_HEIGHT = 64.dp
private val ACCENT_STRIP_WIDTH = 4.dp
private val TEXT_LEADING_GAP = 16.dp
private val TEXT_TRAILING_PADDING = 16.dp
private val TEXT_VERTICAL_GAP = 2.dp
