package `is`.walt.passes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.ui.core.isolated
import `is`.walt.passes.ui.theme.LocalPassesSemantics
import `is`.walt.passes.ui.theme.toComposeColor

/**
 * Home-lane tile for a [ScannableCard]. The visual contract for this surface lives in
 * `docs/SCANNABLE_CARD_THREAT_MODEL.md` C1 / C2: render as a different artifact class
 * from a verified PKPASS tile through redundant means so theming any single dimension
 * flat cannot collapse the distinction. Four redundant distinguishing elements are
 * applied here:
 *
 *  1. Dashed outline (vs the solid rounded `Surface` shape of `PassFront`).
 *  2. Leading [accent]-colored band along the start edge (vs the pass's full
 *     [PassColors] background).
 *  3. Smaller corner radius (8 dp vs the pass's 16 dp), mirroring `DocumentTile`'s
 *     same "not a pass" cue.
 *  4. The "Created by you" caption ([ScannableCardTrustCaption], text-only since the
 *     wpass-v3u restyle), composed unconditionally inside the tile so the trust signal
 *     travels with the artifact and cannot be hidden by a host. The tile has no
 *     placement parameter; the `HostedTypeRow` concession applies only to the detail
 *     surface (`ScannableCardScreen`), so the caption here is always rendered.
 *
 * The caption is always rendered, [maxLines = 1], `softWrap = false`, and overflow
 * [TextOverflow.Visible] so it does not truncate at any tile size — the trust caption
 * is the load-bearing one of the four distinguishers; collapsing it would re-create the
 * verified/unverified visual-conflation risk.
 *
 * The displayed [ScannableCard.label] is user-controlled text and is wrapped in
 * U+2068/U+2069 (FSI/PDI) by `passes-ui-core::isolated` so a malicious label carrying
 * directional-format characters cannot reorder surrounding chrome glyphs. Same defense
 * `DocumentTile` and the security sheets apply to user-controlled strings. The kernel's
 * `ScannableCardCreateInput` validator already rejects Cf/Cc codepoints at the create
 * boundary (C3); this is belt-and-suspenders.
 *
 * The barcode preview embedded here uses a small fixed render size — these previews are
 * a visual identifier ("which card is this"), not a scan surface. The full-screen
 * [ScannableCardScreen] is the scan target; tapping the tile is what gets the user
 * there (the [onClick] callback is the consumer's hook for that navigation).
 *
 * No share / export affordance, no overflow menu, no delete button. Consumer-side
 * lifecycle actions (rename, delete) are walt-android responsibilities and out of scope
 * for the kernel surface.
 */
@Composable
public fun ScannableCardTile(
    card: ScannableCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalPassesSemantics.current.unverifiedArtifact
    val accent = style.accent.toComposeColor()

    Surface(
        modifier = modifier
            .width(TILE_WIDTH)
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(TILE_CORNER_RADIUS),
        tonalElevation = 0.dp,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Leading accent band — visual distinguisher (2). Drawn as a thin filled
            // Box rather than a side border so it reads as a deliberate stripe.
            Box(
                modifier = Modifier
                    .width(ACCENT_BAND_WIDTH)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Dashed outline — visual distinguisher (1). Drawn around the
                    // content column (inside the leading band) so the dash stroke does
                    // not visually compete with the band.
                    .dashedBorder(
                        strokeWidthDp = DASHED_BORDER_STROKE,
                        dashOnDp = DASHED_DASH_ON,
                        dashOffDp = DASHED_DASH_OFF,
                        color = accent,
                        cornerRadiusDp = INNER_CORNER_RADIUS,
                    )
                    .padding(CONTENT_PADDING),
                verticalArrangement = Arrangement.spacedBy(CONTENT_VERTICAL_SPACING),
            ) {
                // Barcode preview: small, identification-only. Centered so a 1D
                // barcode (which renders short and wide) and a QR (square) share the
                // same visual slot without one drifting off-center.
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val (w, h) = card.format.previewSize()
                    ScannableCardView(
                        card = card,
                        // requiredSize wins over ScannableCardView's internal
                        // defaultMinSize, so the preview honors the tile-scoped size
                        // even though the full surface would render larger.
                        //
                        // clearAndSetSemantics drops the barcode's own contentDescription
                        // (it carries `card.label` for standalone use) so TalkBack does
                        // not announce the label twice — once for the barcode image,
                        // again for the sibling Text below. Mirrors DocumentTile's
                        // decorative-thumbnail stance.
                        modifier = Modifier
                            .requiredSize(width = w, height = h)
                            .clearAndSetSemantics {},
                    )
                }

                Text(
                    // User-controlled label. The kernel's validator (passes-core
                    // ScannableCardCreateInput) already strips Cf/Cc, but isolating
                    // here is required defense-in-depth: a future change to the
                    // validator that loosens that rule cannot silently weaken this
                    // surface.
                    text = isolated(card.label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )

                ScannableCardTrustCaption()
            }
        }
    }
}

/**
 * Compose's stock `Modifier.border` does not accept a dash effect, so we paint the
 * dashed rectangle directly via `drawBehind`. Stroke is centered on the path (Compose
 * default), so a stroke of width *w* extends *w/2* outside and *w/2* inside the rect —
 * inset by half the stroke so the visible dashes sit fully within the column bounds.
 */
private fun Modifier.dashedBorder(
    strokeWidthDp: Dp,
    dashOnDp: Dp,
    dashOffDp: Dp,
    color: Color,
    cornerRadiusDp: Dp,
): Modifier = drawBehind {
    val stroke = strokeWidthDp.toPx()
    val on = dashOnDp.toPx()
    val off = dashOffDp.toPx()
    val radius = cornerRadiusDp.toPx()
    val effect = PathEffect.dashPathEffect(floatArrayOf(on, off), 0f)
    val inset = stroke / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = stroke, pathEffect = effect),
    )
}

private fun ScannableFormat.previewSize(): Pair<Dp, Dp> = when (this) {
    ScannableFormat.Qr -> 96.dp to 96.dp
    ScannableFormat.Code128,
    ScannableFormat.Ean13,
    ScannableFormat.UpcA,
    ScannableFormat.Code39,
    -> 132.dp to 40.dp
}

private val TILE_WIDTH = 168.dp
private val TILE_CORNER_RADIUS = 8.dp
private val INNER_CORNER_RADIUS = 6.dp
private val ACCENT_BAND_WIDTH = 4.dp
private val DASHED_BORDER_STROKE = 1.5.dp
private val DASHED_DASH_ON = 6.dp
private val DASHED_DASH_OFF = 4.dp
private val CONTENT_PADDING = 10.dp
private val CONTENT_VERTICAL_SPACING = 8.dp
