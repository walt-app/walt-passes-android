package `is`.walt.passes.ui.internal

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import `is`.walt.passes.core.LocalizedStrings

/**
 * The pass.strings table for the currently-rendering pass, threaded down through
 * Compose so nested field-cell composables (header rows, primary fields, body rows,
 * back-field rows) can substitute placeholder labels without taking the table as an
 * extra parameter. wpass-38y.
 *
 * The default is [LocalizedStrings.Empty], which makes reads outside a provider a safe
 * passthrough: [LocalizedStrings.lookupOrSelf] on the empty table returns the raw
 * input. The two top-level pass composables ([PassFront], [PassBack]) provide the
 * resolved table; nested helpers read it through this accessor.
 *
 * Internal because it is a render-time scaffolding detail, not a public theming knob —
 * unlike [LocalPassesSemantics] which the host wires once at app root.
 */
internal val LocalLocalizedStrings: ProvidableCompositionLocal<LocalizedStrings> =
    staticCompositionLocalOf { LocalizedStrings.Empty }
