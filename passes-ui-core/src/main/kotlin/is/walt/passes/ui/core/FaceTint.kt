package `is`.walt.passes.ui.core

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified

/**
 * Whether [faceTint] is a tint a card face should actually take, for the consumer-supplied
 * face tints of the 26.08.08 colour system (`ScannableCardScreen` / `DocumentView`).
 *
 * [Color.isSpecified] alone is not the gate: it is true for [Color.Transparent], so a
 * consumer handing over a cleared or not-yet-loaded colour would take the tinted branch and
 * paint nothing — host paint shows through, and a surface deriving ink from the tint reads
 * luminance 0 and picks white ink over whatever the host painted. The alpha check sends both
 * cases to the documented untinted default instead.
 *
 * Shared here rather than duplicated per surface. The two arms landed a day apart with
 * independent gates and diverged on exactly this case (wpass-80y.5); `passes-ui-core` exists
 * so `passes-ui` and `passes-document-ui` can share a primitive without depending on each
 * other. Not trust-claim-bearing: neither surface can lose its trust caption either way, so
 * this decides legibility, not provenance.
 */
public fun faceIsTinted(faceTint: Color): Boolean =
    faceTint.isSpecified && faceTint.alpha > 0f
