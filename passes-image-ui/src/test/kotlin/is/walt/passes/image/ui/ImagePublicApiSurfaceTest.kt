package `is`.walt.passes.image.ui

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Classpath-scan tests that enforce the no-share-out discipline for image surfaces,
 * mirroring `passes-pdf-ui::DocumentPublicApiSurfaceTest`. The scan covers both
 * `passes-image-ui` and verifies no `Intent.ACTION_SEND` construction exists — images
 * stored in Walt have no egress path back to a sharing intent.
 */
class ImagePublicApiSurfaceTest {

    @Test
    fun passesImageUiDoesNotDeclareActionSendIntent() {
        // Scan the module's own class files for Intent.ACTION_SEND references.
        // A constant-string search on the classpath is sufficient because
        // Intent.ACTION_SEND = "android.intent.action.SEND" is always inlined as a
        // literal at the call site. Any construction of a share intent would embed the
        // string in the class file.
        val violations = mutableListOf<String>()
        val classLoader = ImagePublicApiSurfaceTest::class.java.classLoader!!
        val modulePackage = "is/walt/passes/image/ui"
        val resources = classLoader.getResources(modulePackage)
        resources.asSequence().forEach { url ->
            val root = java.io.File(url.toURI())
            if (!root.exists()) return@forEach
            root.walkTopDown()
                .filter { it.extension == "class" }
                .forEach { classFile ->
                    val bytes = classFile.readBytes()
                    val content = String(bytes, Charsets.ISO_8859_1)
                    if (content.contains("android.intent.action.SEND")) {
                        violations += classFile.name
                    }
                }
        }
        assertWithMessage(
            "passes-image-ui must not construct Intent.ACTION_SEND. " +
                "Images have no share-out path. Violations: $violations",
        ).that(violations).isEmpty()
    }

    @Test
    fun imageDocumentIsNotAssignableToPass() {
        val passClass = runCatching {
            Class.forName("is.walt.passes.core.Pass")
        }.getOrNull() ?: return // passes-core not on classpath in isolated test run — skip

        val imageDocumentClass = Class.forName("is.walt.passes.image.ImageDocument")
        assertWithMessage(
            "ImageDocument must not be a subtype of Pass. They are sibling concepts " +
                "with different trust models.",
        ).that(passClass.isAssignableFrom(imageDocumentClass)).isFalse()
    }
}
