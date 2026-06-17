package `is`.walt.passes.document.ui

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
import `is`.walt.passes.document.ui.internal.InfoOutlineIcon
import `is`.walt.passes.document.ui.theme.LocalDocumentSemantics
import `is`.walt.passes.ui.core.toComposeColor

/**
 * The non-suppressible "this is a user-supplied document" caption that anchors the
 * trust contract of the PDF surface (ADR 0005 D5 / D8): a PDF rendered by Walt is
 * never signature-verified, has no attestable origin, and is presented under a fixed
 * caption that the user cannot dismiss and the host cannot hide.
 *
 * The composable has no `enabled` parameter, no theme token that hides it, and no
 * `DocumentView` overload that skips rendering it. Mirrors `ExpiredOverlay`'s shape:
 * the trust claim is structural, not a configuration. Adding a parameter to this
 * function fails `DocumentSurfaceLockTest`; adding an overload fails the same lock
 * (which counts the largest method named `DocumentTrustCaption` and asserts the
 * exact arity).
 *
 * Layout is a flat [Row] of an info-outline icon followed by the caption text, both
 * center-aligned, with the [Row] still painting `captionBackground` behind itself. A
 * consumer that wants the historical filled colour-block keeps a non-transparent
 * `captionBackground`; a consumer matching a flat, borderless house style sets
 * `captionBackground` transparent and the caption reads as inline chrome rather than
 * a separate surface. This is a RESTYLE only — the caption is composed at exactly the
 * same sites, the wording is byte-for-byte unchanged and still a single [Text] node,
 * and there is still no way to suppress it (ADR 0005 D5; no ADR amendment needed
 * because non-suppressibility is unchanged).
 *
 * The icon tint is its own theme slot, `DocumentSemantics.captionIconTint`, defaulted
 * to `captionForeground` so a consumer that does not care gets a consistent
 * monochrome caption for free, while a consumer that wants an accent-coloured glyph
 * sets it explicitly. The glyph itself is hand-authored in-module (see
 * `internal.InfoOutlineIcon`) so this module does not pull in `material-icons-extended`.
 *
 * The displayed text is a fixed English literal; no part of it comes from the
 * document. There is therefore no BiDi isolation here — nothing user-controlled to
 * isolate. The user-controlled `displayLabel` is wrapped by `DocumentTile` /
 * `DocumentView` at their own boundaries; see `passes-ui-core::isolated`.
 */
@Composable
public fun DocumentTrustCaption(
    modifier: Modifier = Modifier,
) {
    val semantics = LocalDocumentSemantics.current
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
            // Decorative: the verbatim caption text sits immediately beside it and is
            // the audit-relevant semantics node. A contentDescription here would only
            // add a redundant TalkBack stop on top of the text.
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
 * matches the trust claim verbatim. Wording is the load-bearing part of ADR 0005 D5;
 * a contributor changing this string is making a security-policy edit and the test
 * suite will require them to update the assertion.
 *
 * Note on dual-anchor placement: the caption is composed BOTH inside `DocumentsLane`
 * (so the wallet-list user sees it before tapping any document) AND inside
 * `DocumentView` (so a deep-linked or shortcut-launched DocumentView does not bypass
 * it). The duplication is deliberate; do NOT refactor it to a single render site —
 * each composition site is an independent trust anchor and removing one collapses the
 * defense.
 */
internal const val TRUST_CAPTION_TEXT: String =
    "User-provided document. Walt has not verified the source."
