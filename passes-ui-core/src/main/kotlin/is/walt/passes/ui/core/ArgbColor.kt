package `is`.walt.passes.ui.core

import androidx.compose.ui.graphics.Color

/**
 * A 32-bit ARGB color, packed `0xAARRGGBB`. Mirrors `passes-core`'s `ColorValue.rgb`
 * shape but with an alpha channel, since theme tokens may legitimately want to
 * express transparency that pass.json's RGB triplet cannot.
 *
 * Lives in `passes-ui-core` so both `passes-ui` (PKPASS theme tokens) and
 * `passes-document-ui` (document theme tokens) can share the same ARGB shape without
 * either module depending on the other. The kdoc on the surface modules' theme
 * data classes describes how each slot is consumed.
 *
 * Compose-side conversion: [toComposeColor]. The `Long` cast inside is required
 * because `Color(Int)` interprets its argument as RGB without alpha, while
 * `Color(Long)` preserves the alpha channel.
 */
@JvmInline
public value class ArgbColor(public val argb: Int)

/**
 * Convert a packed-ARGB [ArgbColor] (the contract type) to a Compose [Color] (the
 * runtime type). The `.toLong()` cast is required: `Color(Int)` interprets the int
 * as RGB without alpha, while `Color(Long)` preserves the alpha channel.
 */
public fun ArgbColor.toComposeColor(): Color = Color(argb.toLong() and 0xFFFFFFFFL)
