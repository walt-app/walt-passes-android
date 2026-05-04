package `is`.walt.passes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.PassLocale
import `is`.walt.passes.ui.theme.LocalPassesSemantics
import `is`.walt.passes.ui.theme.toComposeColor

/**
 * Renders the non-suppressible expired/voided overlay over the parent. [state] is the
 * outcome of [ExpiredOverlayState.from]; [ExpiredOverlayState.None] renders nothing.
 *
 * The composable has no `enabled` parameter and no caller-supplied flag that would
 * hide the overlay — see ADR 0003 D5. A pass whose validity window has closed cannot
 * present as valid through any path in this API.
 */
@Composable
public fun ExpiredOverlay(
    state: ExpiredOverlayState,
    @Suppress("UNUSED_PARAMETER") locale: PassLocale = PassLocale("en"),
    modifier: Modifier = Modifier,
) {
    if (state is ExpiredOverlayState.None) return

    val style = LocalPassesSemantics.current.expiredBadge
    val scrim = Color.Black.copy(alpha = (style.scrimAlpha.coerceIn(0, 255)) / 255f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(scrim),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when (state) {
                ExpiredOverlayState.Voided -> "Voided"
                is ExpiredOverlayState.Expired -> "Expired"
                ExpiredOverlayState.None -> ""
            },
            color = style.pillForeground.toComposeColor(),
            modifier = Modifier
                .clip(RoundedCornerShape(100.dp))
                .background(style.pillBackground.toComposeColor())
                .padding(PaddingValues(horizontal = 24.dp, vertical = 8.dp)),
        )
    }
}

/** Public for use as a sentinel in tests; no other purpose. */
@Suppress("unused")
internal val expiredOverlayMarkerColor: Int = Color.Black.toArgb()
