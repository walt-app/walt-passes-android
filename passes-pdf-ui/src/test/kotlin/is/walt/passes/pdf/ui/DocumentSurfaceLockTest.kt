package `is`.walt.passes.pdf.ui

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.lang.reflect.Method

/**
 * Pins the parameter-shape discipline of the trust-claim-bearing document composables
 * (ADR 0005 D5 / D8). These shape constraints ARE the trust contract: a future
 * refactor that adds `showCaption: Boolean = false` to `DocumentView`, or `showShare:
 * Boolean = false` overflow-menu hooks to `DocumentTile`, would silently weaken the
 * claim documented in TRUST_CLAIMS.md.
 *
 * Carved out of `passes-ui::ComposableSurfaceLockTest` (wpass-r4z) when the document
 * composables moved into their own module. The PKPASS surfaces' shape locks stay over
 * in `passes-ui`; here we lock just the document surfaces, and reach them via Java
 * reflection to dodge the Compose-compiler-plugin's interaction with Kotlin
 * reflection on top-level `@Composable` functions.
 */
class DocumentSurfaceLockTest {

    @Test
    fun documentTrustCaptionHasExactlyOneUserVisibleParameter() {
        // (modifier) — D5: no `enabled`, no theme suppression flag, no overload that
        // accepts a state to hide the caption. The caption is structurally always-on.
        assertUserVisibleParamCount("DocumentTrustCaptionKt", "DocumentTrustCaption", expected = 1)
    }

    @Test
    fun documentTileHasExactlyFourUserVisibleParameters() {
        // (doc, thumbnail, onClick, modifier). No share/export action, no overflow menu.
        assertUserVisibleParamCount("DocumentTileKt", "DocumentTile", expected = 4)
    }

    @Test
    fun documentViewHasExactlySixUserVisibleParameters() {
        // (doc, pdfFile, renderer, modifier, telemetry, onOpenFullScreen). D5: still no
        // flag to hide the trust caption. The sixth slot is the wpass-jil full-screen
        // navigation callback with a `null` default — when null the banner is hidden,
        // when non-null the banner appears and invokes the callback on tap. Bumping
        // from 5 to 6 is the deliberate change for that issue, not a slip.
        assertUserVisibleParamCount("DocumentViewKt", "DocumentView", expected = 6)
    }

    @Test
    fun documentsLaneHasExactlyFourUserVisibleParameters() {
        // (documents, thumbnails, onDocumentClick, modifier). The lane composes the
        // trust caption inside itself; no parameter omits it.
        assertUserVisibleParamCount("DocumentsLaneKt", "DocumentsLane", expected = 4)
    }

    @Test
    fun documentViewConsumesPdfRendererBinderInterfaceNotConcreteClient() {
        // The DocumentView contract takes the binder interface so test fakes inject
        // cleanly. A regression to the concrete `PdfRendererClient` would make every
        // hosting test bind a real service. Lock the type by simple name.
        val method = findComposable("DocumentViewKt", "DocumentView")
        val typeNames = method.parameterTypes.map { it.simpleName }
        assertWithMessage("DocumentView must accept the PdfRendererBinder interface")
            .that(typeNames)
            .contains("PdfRendererBinder")
        assertWithMessage("DocumentView must NOT bind to the concrete PdfRendererClient")
            .that(typeNames)
            .doesNotContain("PdfRendererClient")
    }

    @Test
    fun fullScreenDocumentViewHasExactlySevenUserVisibleParameters() {
        // (doc, pdfFile, renderer, onClose, modifier, telemetry, closeButton). D5:
        // trust caption is composed inside the surface; no parameter omits it. Required
        // onClose forces the host to provide a back path — there is no "stuck in
        // full-screen" state. The seventh slot is a host-supplied close-button
        // composable (icon vs. text, host chrome) that does not touch the trust caption
        // or any other surface affordance, so D5 is unaffected. Bumping from 6 to 7 is
        // the deliberate change for that slot, not a slip.
        assertUserVisibleParamCount(
            "FullScreenDocumentViewKt",
            "FullScreenDocumentView",
            expected = 7,
        )
    }

    @Test
    fun fullScreenDocumentViewConsumesPdfRendererBinderInterfaceNotConcreteClient() {
        // Same contract as DocumentView: the full-screen surface takes the binder
        // interface so test fakes inject cleanly.
        val method = findComposable("FullScreenDocumentViewKt", "FullScreenDocumentView")
        val typeNames = method.parameterTypes.map { it.simpleName }
        assertWithMessage("FullScreenDocumentView must accept the PdfRendererBinder interface")
            .that(typeNames)
            .contains("PdfRendererBinder")
        assertWithMessage("FullScreenDocumentView must NOT bind to the concrete PdfRendererClient")
            .that(typeNames)
            .doesNotContain("PdfRendererClient")
    }

    @Test
    fun documentSurfacesHaveNoOverloads() {
        // The trust-caption non-suppressibility rule extends to overloads: a future
        // contributor cannot quietly add `DocumentView(..., showCaption: Boolean)` as
        // a sibling with the same name. Lock that there is exactly one method per
        // composable name.
        listOf(
            "DocumentTrustCaptionKt" to "DocumentTrustCaption",
            "DocumentTileKt" to "DocumentTile",
            "DocumentViewKt" to "DocumentView",
            "DocumentsLaneKt" to "DocumentsLane",
            "FullScreenDocumentViewKt" to "FullScreenDocumentView",
        ).forEach { (file, name) ->
            val klass = Class.forName("is.walt.passes.pdf.ui.$file")
            val matches = klass.methods.filter { it.name == name || it.name.startsWith("$name-") }
            assertWithMessage("$name should have exactly one declared overload")
                .that(matches.size)
                .isEqualTo(1)
        }
    }

    // -- helpers -------------------------------------------------------------------

    private fun findComposable(fileClassSimpleName: String, methodName: String): Method {
        val klass = Class.forName("is.walt.passes.pdf.ui.$fileClassSimpleName")
        // Kotlin's value-class name mangling appends `-<hash>` to a JVM method name
        // when the function takes a value-class parameter. Match either the bare name
        // or the mangled prefix.
        return klass.methods
            .filter { it.name == methodName || it.name.startsWith("$methodName-") }
            .maxByOrNull { it.parameterCount }
            ?: error("Composable $fileClassSimpleName.$methodName not found")
    }

    private fun userVisibleParameterCount(method: Method): Int {
        // Compose appends `Composer $composer` and `int $changed` (and `int $changed1`
        // for high-arity functions). Strip them by their JVM type names.
        val params = method.parameterTypes
        var count = params.size
        var i = params.lastIndex
        while (i >= 0) {
            val t = params[i]
            val isComposer = t.name == "androidx.compose.runtime.Composer"
            val isInt = t.name == "int"
            if (!isComposer && !isInt) break
            count--
            i--
        }
        return count
    }

    private fun assertUserVisibleParamCount(
        fileClassSimpleName: String,
        methodName: String,
        expected: Int,
    ) {
        val method = findComposable(fileClassSimpleName, methodName)
        val actual = userVisibleParameterCount(method)
        assertWithMessage(
            "$methodName user-visible parameter count drifted; review ADR 0005 D5/D8 " +
                "before changing this number. Full method signature: $method",
        )
            .that(actual)
            .isEqualTo(expected)
    }
}
