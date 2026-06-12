package `is`.walt.passes.image.ui

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
import androidx.compose.ui.unit.dp
import `is`.walt.passes.image.ui.internal.InfoOutlineIcon
import `is`.walt.passes.image.ui.theme.LocalImageSemantics
import `is`.walt.passes.ui.core.toComposeColor

/**
 * The non-suppressible "this is a user-supplied image" caption that anchors the trust
 * contract of the image surface: an image stored in Walt is never signature-verified,
 * has no attestable origin, and is presented under a fixed caption that the user cannot
 * dismiss and the host cannot hide.
 *
 * The composable has no `enabled` parameter, no theme token that hides it, and no
 * [ImageDocumentView] overload that skips rendering it. Mirrors [is.walt.passes.pdf.ui.DocumentTrustCaption]'s
 * shape: the trust claim is structural, not a configuration. Adding a parameter to this
 * function fails [ImageSurfaceLockTest]; adding an overload fails the same lock.
 *
 * The displayed text is a fixed English literal; no part of it comes from the image.
 * There is therefore no BiDi isolation here. User-controlled display labels are wrapped
 * at their own render sites in [ImageDocumentTile] and [ImageDocumentView].
 */
@Composable
public fun ImageTrustCaption(
    modifier: Modifier = Modifier,
) {
    val semantics = LocalImageSemantics.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(semantics.captionBackground.toComposeColor())
            .padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = InfoOutlineIcon,
            contentDescription = null,
            tint = semantics.captionIconTint.toComposeColor(),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = TRUST_CAPTION_TEXT,
            style = MaterialTheme.typography.labelMedium,
            color = semantics.captionForeground.toComposeColor(),
        )
    }
}

/**
 * The exact caption copy. Public-internal so tests can assert the displayed text
 * matches the trust claim verbatim. Wording is the load-bearing part of the image
 * trust contract; a contributor changing this string is making a security-policy edit
 * and the test suite will require them to update the assertion.
 *
 * The caption is composed BOTH inside [ImageDocumentsLane] (so the wallet-list user
 * sees it before tapping any image) AND inside [ImageDocumentView] (so a deep-linked
 * view does not bypass it). The duplication is deliberate — each is an independent
 * trust anchor.
 */
internal const val TRUST_CAPTION_TEXT: String =
    "User-provided image. Walt has not verified the source."
