package `is`.walt.passes.pdf.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The host's entry point into `passes-pdf-ui`. Wraps [content] in a
 * [CompositionLocalProvider] that supplies [LocalDocumentSemantics]. Every
 * `passes-pdf-ui` composable expects to be called inside this scope; reading
 * [LocalDocumentSemantics] outside of it fails fast.
 *
 * Sibling to `passes-ui::PassesTheme`; a host rendering both passes and documents
 * wires both at the screen-graph root, e.g.:
 *
 * ```
 * PassesTheme(semantics = walledPassesSemantics) {
 *     DocumentTheme(semantics = walledDocumentSemantics) {
 *         AppContent()
 *     }
 * }
 * ```
 *
 * Color and typography for general chrome are supplied by the host's surrounding
 * `MaterialTheme`; `DocumentTheme` does not redeclare them. [semantics] is the small
 * set of slots that have no Material3 analogue — see [DocumentSemantics].
 */
@Composable
public fun DocumentTheme(
    semantics: DocumentSemantics,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalDocumentSemantics provides semantics,
        content = content,
    )
}

/**
 * Composition-local accessor for the host-supplied [DocumentSemantics]. Reading this
 * outside a [DocumentTheme] scope throws — fail-fast keeps a forgotten
 * `DocumentTheme(...)` scope from silently rendering with a developer-default trust-
 * caption palette.
 */
public val LocalDocumentSemantics: ProvidableCompositionLocal<DocumentSemantics> =
    staticCompositionLocalOf {
        error(
            "LocalDocumentSemantics not provided. Wrap document-rendering composables " +
                "in DocumentTheme(semantics = ...) at the host root (typically inside " +
                "the host's MaterialTheme scope and alongside any PassesTheme scope).",
        )
    }
