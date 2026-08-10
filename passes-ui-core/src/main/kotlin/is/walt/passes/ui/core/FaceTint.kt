package `is`.walt.passes.ui.core

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified

/**
 * Whether [faceTint] is a tint a card face should actually take — the gate for the
 * consumer-supplied face tints on `passes-ui`'s scannable face and `passes-document-ui`'s
 * document frame.
 *
 * [Color.isSpecified] alone is not the gate: it is true for [Color.Transparent], which would
 * paint nothing and leave ink derived from luminance 0. Shared by both surfaces so they
 * cannot drift on that case. Not trust-claim-bearing — neither surface can lose its trust
 * caption either way, so this decides legibility, not provenance.
 */
public fun faceIsTinted(faceTint: Color): Boolean =
    faceTint.isSpecified && faceTint.alpha > 0f
