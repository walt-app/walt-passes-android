package `is`.walt.passes.image.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The host's entry point into `passes-image-ui`. Wraps [content] in a
 * [CompositionLocalProvider] that supplies [LocalImageSemantics]. Every
 * `passes-image-ui` composable expects to be called inside this scope; reading
 * [LocalImageSemantics] outside of it fails fast.
 *
 * Sibling to `passes-ui::PassesTheme` and `passes-pdf-ui::DocumentTheme`; a host
 * rendering passes, documents, and images wires all three at the screen-graph root:
 *
 * ```
 * PassesTheme(semantics = passesSemantics) {
 *     DocumentTheme(semantics = documentSemantics) {
 *         ImageTheme(semantics = imageSemantics) {
 *             AppContent()
 *         }
 *     }
 * }
 * ```
 *
 * Color and typography for general chrome are supplied by the host's surrounding
 * `MaterialTheme`; [ImageTheme] does not redeclare them.
 */
@Composable
public fun ImageTheme(
    semantics: ImageSemantics,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalImageSemantics provides semantics,
        content = content,
    )
}

/**
 * Composition-local accessor for the host-supplied [ImageSemantics]. Reading this
 * outside an [ImageTheme] scope throws — fail-fast keeps a forgotten `ImageTheme(...)`
 * scope from silently rendering with a developer-default trust-caption palette.
 */
public val LocalImageSemantics: ProvidableCompositionLocal<ImageSemantics> =
    staticCompositionLocalOf {
        error(
            "LocalImageSemantics not provided. Wrap image-rendering composables in " +
                "ImageTheme(semantics = ...) at the host root (typically inside the " +
                "host's MaterialTheme scope and alongside any PassesTheme / DocumentTheme scope).",
        )
    }
