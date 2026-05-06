package `is`.walt.passes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `is`.walt.passes.ui.theme.LocalPassesSemantics
import `is`.walt.passes.ui.theme.toComposeColor

/**
 * The non-suppressible "this is a user-supplied document" caption that anchors the
 * trust contract of the PDF surface (ADR 0005 D5 / D8): a PDF rendered by Walt is
 * never signature-verified, has no attestable origin, and is presented under a fixed
 * caption that the user cannot dismiss and the host cannot hide.
 *
 * The composable has no `enabled` parameter, no theme token that hides it, and no
 * `DocumentView` overload that skips rendering it. Mirrors `ExpiredOverlay`'s shape:
 * the trust claim is structural, not a configuration. Adding a parameter to this
 * function fails `ComposableSurfaceLockTest`; adding an overload fails the same lock
 * (which counts the largest method named `DocumentTrustCaption` and asserts the
 * exact arity).
 *
 * The displayed text is a fixed English literal; no part of it comes from the
 * document. There is therefore no BiDi isolation here — nothing user-controlled to
 * isolate. The user-controlled `displayLabel` is wrapped by `DocumentTile` /
 * `DocumentView` at their own boundaries; see the FSI/PDI utility shared with
 * `SecuritySheets`.
 */
@Composable
public fun DocumentTrustCaption(
    modifier: Modifier = Modifier,
) {
    val semantics = LocalPassesSemantics.current.documents
    Text(
        text = TRUST_CAPTION_TEXT,
        style = MaterialTheme.typography.labelMedium,
        color = semantics.captionForeground.toComposeColor(),
        modifier = modifier
            .fillMaxWidth()
            .background(semantics.captionBackground.toComposeColor())
            .padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
    )
}

/**
 * The exact caption copy. Public-internal so tests can assert the displayed text
 * matches the trust claim verbatim. Wording is the load-bearing part of ADR 0005 D5;
 * a contributor changing this string is making a security-policy edit and the test
 * suite will require them to update the assertion.
 */
internal const val TRUST_CAPTION_TEXT: String =
    "User-provided document. Walt has not verified the source."
