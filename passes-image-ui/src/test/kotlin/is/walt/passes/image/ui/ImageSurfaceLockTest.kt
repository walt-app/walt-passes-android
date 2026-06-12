package `is`.walt.passes.image.ui

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.lang.reflect.Method

/**
 * Pins the parameter-shape discipline of the trust-claim-bearing image composables.
 * These shape constraints ARE the trust contract: a future refactor that adds
 * `showCaption: Boolean = false` to [ImageDocumentView], or share/export hooks to
 * [ImageDocumentTile], would silently weaken the claim. Mirrors the shape of
 * `passes-pdf-ui::DocumentSurfaceLockTest`.
 */
class ImageSurfaceLockTest {

    @Test
    fun imageTrustCaptionHasExactlyOneUserVisibleParameter() {
        // (modifier) — no `enabled`, no suppression flag.
        assertUserVisibleParamCount("ImageTrustCaptionKt", "ImageTrustCaption", expected = 1)
    }

    @Test
    fun imageDocumentTileHasExactlyFourUserVisibleParameters() {
        // (doc, thumbnail, onClick, modifier). No share/export action.
        assertUserVisibleParamCount("ImageDocumentTileKt", "ImageDocumentTile", expected = 4)
    }

    @Test
    fun imageDocumentViewHasExactlyThreeUserVisibleParameters() {
        // (doc, image, modifier). No flag to hide the trust caption.
        assertUserVisibleParamCount("ImageDocumentViewKt", "ImageDocumentView", expected = 3)
    }

    @Test
    fun imageDocumentsLaneHasExactlyFourUserVisibleParameters() {
        // (images, thumbnails, onImageClick, modifier). Caption composed inside the lane.
        assertUserVisibleParamCount("ImageDocumentsLaneKt", "ImageDocumentsLane", expected = 4)
    }

    @Test
    fun imageSurfacesHaveNoOverloads() {
        listOf(
            "ImageTrustCaptionKt" to "ImageTrustCaption",
            "ImageDocumentTileKt" to "ImageDocumentTile",
            "ImageDocumentViewKt" to "ImageDocumentView",
            "ImageDocumentsLaneKt" to "ImageDocumentsLane",
        ).forEach { (file, name) ->
            val klass = Class.forName("is.walt.passes.image.ui.$file")
            val matches = klass.methods.filter { it.name == name || it.name.startsWith("$name-") }
            assertWithMessage("$name should have exactly one declared overload")
                .that(matches.size)
                .isEqualTo(1)
        }
    }

    // -- helpers -------------------------------------------------------------------

    private fun findComposable(fileClassSimpleName: String, methodName: String): Method {
        val klass = Class.forName("is.walt.passes.image.ui.$fileClassSimpleName")
        return klass.methods
            .filter { it.name == methodName || it.name.startsWith("$methodName-") }
            .maxByOrNull { it.parameterCount }
            ?: error("Composable $fileClassSimpleName.$methodName not found")
    }

    private fun userVisibleParameterCount(method: Method): Int {
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
            "$methodName user-visible parameter count drifted; review the image trust " +
                "contract before changing this number. Full method signature: $method",
        )
            .that(actual)
            .isEqualTo(expected)
    }
}
