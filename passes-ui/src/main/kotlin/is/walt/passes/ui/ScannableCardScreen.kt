package `is`.walt.passes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.ui.core.isolated

/**
 * Full-screen surface for scanning a [ScannableCard]. Wraps [ScannableCardView] with
 * minimal chrome: the user-controlled label up top (FSI/PDI isolated), the barcode
 * itself rendered at its full nominal size on a content-sized white backing, and the
 * non-suppressible [ScannableCardTrustCaption] docked at the bottom.
 *
 * The white backing is sized to the code (plus a quiet-zone margin), not to the whole
 * screen (wpass-1wu.2 / Walt wlt-n5z): the rest of the surface is transparent so the
 * host's background shows through instead of a full white wash in a tall slot. ZXing
 * bakes the scan quiet zone into the matrix (`ZxingBarcodeEncoder`), so scannability is
 * preserved; [CODE_QUIET_ZONE] adds visual breathing room inside the card and the
 * QR/1D `ContentScale` split in [ScannableCardView] is unchanged.
 *
 * Trust contract: the caption is composed unconditionally at the bottom of the screen
 * (C2 in `docs/SCANNABLE_CARD_THREAT_MODEL.md`), structurally separate from any host
 * navigation chrome. There is no parameter, theme token, or overload that hides it.
 * [showLabel] gates ONLY the top label `Text`; it cannot suppress the barcode, the
 * payload caption, or the trust caption.
 *
 * No share / save-to-photos / print affordance, and no overflow menu. The user came
 * here to scan, then back out — those are the only two paths off this surface. Host
 * navigation chrome (back button, screen title) is supplied by the consumer's
 * scaffold; this composable is the body only.
 *
 * @param showLabel when false, the built-in label is not rendered. Defaults to true so
 *   every existing caller is unchanged. Hosts that render their own title above this
 *   surface (e.g. an editable self-title) pass false to avoid a duplicate (Walt wlt-tct).
 */
@Composable
public fun ScannableCardScreen(
    card: ScannableCard,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        if (showLabel) {
            Text(
                text = isolated(card.label),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
            ) {
                // POS-scan fallback for GH #102; only the detail surface is large
                // enough. A11y rationale lives on ScannableCardView.
                ScannableCardView(
                    card = card,
                    showPayloadCaption = true,
                    modifier = Modifier.padding(CODE_QUIET_ZONE),
                )
            }
        }

        ScannableCardTrustCaption(modifier = Modifier.fillMaxWidth())
    }
}

/** White-card padding around the code. The scan quiet zone is in the matrix; this is visual. */
private val CODE_QUIET_ZONE = 16.dp
