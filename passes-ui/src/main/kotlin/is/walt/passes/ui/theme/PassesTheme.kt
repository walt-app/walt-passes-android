package `is`.walt.passes.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The host's entry point into `passes-ui`. Wraps [content] in a [CompositionLocalProvider]
 * that supplies [LocalPassesSemantics]. Every passes-ui composable expects to be called
 * inside this scope; reading [LocalPassesSemantics] outside of it fails fast (see ADR 0003
 * D3).
 *
 * Color and typography for general chrome are supplied by the host's surrounding
 * `MaterialTheme` (e.g. walt-android's `WaltTheme`); `PassesTheme` does not redeclare them.
 * [semantics] is the small set of slots that have no Material3 analogue — see
 * [PassesSemantics].
 */
@Composable
public fun PassesTheme(
    semantics: PassesSemantics,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPassesSemantics provides semantics,
        content = content,
    )
}

/**
 * Composition-local accessor for the host-supplied [PassesSemantics]. Reading this
 * outside a [PassesTheme] scope throws — fail-fast keeps a forgotten `PassesTheme(...)`
 * scope from silently rendering with a developer-default trust-badge palette.
 */
public val LocalPassesSemantics: ProvidableCompositionLocal<PassesSemantics> =
    staticCompositionLocalOf {
        error(
            "LocalPassesSemantics not provided. Wrap pass-rendering composables in " +
                "PassesTheme(semantics = ...) at the host root (typically inside the " +
                "host's MaterialTheme scope).",
        )
    }

/**
 * Convert a packed-ARGB [ArgbColor] (the contract type) to a Compose [Color] (the
 * runtime type). The `.toLong()` cast is required: `Color(Int)` interprets the int
 * as RGB without alpha, while `Color(Long)` preserves the alpha channel.
 */
public fun ArgbColor.toComposeColor(): Color = Color(argb.toLong() and 0xFFFFFFFFL)
