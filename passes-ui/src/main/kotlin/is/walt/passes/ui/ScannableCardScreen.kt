package `is`.walt.passes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
 * itself rendered at its full nominal size, and the non-suppressible
 * [ScannableCardTrustCaption] docked at the bottom.
 *
 * Trust contract: the caption is composed unconditionally at the bottom of the screen
 * (C2 in `docs/SCANNABLE_CARD_THREAT_MODEL.md`), structurally separate from any host
 * navigation chrome. There is no parameter, theme token, or overload that hides it.
 *
 * No share / save-to-photos / print affordance, and no overflow menu. The user came
 * here to scan, then back out — those are the only two paths off this surface. Host
 * navigation chrome (back button, screen title) is supplied by the consumer's
 * scaffold; this composable is the body only.
 */
@Composable
public fun ScannableCardScreen(
    card: ScannableCard,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            // POS-scan fallback for GH #102; only the detail surface is large
            // enough. A11y rationale lives on ScannableCardView.
            ScannableCardView(
                card = card,
                showPayloadCaption = true,
            )
        }

        ScannableCardTrustCaption(modifier = Modifier.fillMaxWidth())
    }
}
