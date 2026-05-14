package `is`.walt.passes.pdf.ui

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.pdf.ui.theme.DocumentSemantics
import `is`.walt.passes.ui.core.ArgbColor
import org.junit.Test
import java.io.File

/**
 * Locks the public API surface of `passes-pdf-ui`. Mirrors `passes-ui`'s and
 * `passes-core`'s lock tests: every nested theme field is read so a rename or
 * removal forces a deliberate update, and the bytecode-scan tests fail closed if a
 * future contributor wires a Share / Export / PDF-MIME callsite into this module.
 *
 * Carved out of `passes-ui::PublicApiSurfaceTest` (wpass-r4z) when the document
 * composables moved into their own module. The PKPASS-side scan stays over in
 * `passes-ui`; the equivalent scan for the renderer-service module (passes-pdf)
 * remains where it was.
 */
class DocumentPublicApiSurfaceTest {

    @Test
    fun documentSemanticsDataClassExposesAllNineSlots() {
        val argb = ArgbColor(0xFF000000.toInt())
        // captionIconTint gets a distinct value so the read below proves it is its own
        // independent slot, not an alias of captionForeground (it merely *defaults* to
        // captionForeground when a caller omits it).
        val iconTint = ArgbColor(0xFFFF8800.toInt())
        val semantics = DocumentSemantics(
            captionBackground = argb,
            captionForeground = argb,
            captionIconTint = iconTint,
            tileBackground = argb,
            tileForeground = argb,
            tileLabelForeground = argb,
            laneBackground = argb,
            documentBadgeBackground = argb,
            documentBadgeForeground = argb,
        )
        // Reading every nested field forces them to remain in the public-API shape;
        // a rename or removal breaks the test.
        assertThat(semantics.captionBackground).isEqualTo(argb)
        assertThat(semantics.captionForeground).isEqualTo(argb)
        assertThat(semantics.captionIconTint).isEqualTo(iconTint)
        assertThat(semantics.tileBackground).isEqualTo(argb)
        assertThat(semantics.tileForeground).isEqualTo(argb)
        assertThat(semantics.tileLabelForeground).isEqualTo(argb)
        assertThat(semantics.laneBackground).isEqualTo(argb)
        assertThat(semantics.documentBadgeBackground).isEqualTo(argb)
        assertThat(semantics.documentBadgeForeground).isEqualTo(argb)
    }

    @Test
    fun documentSemanticsCaptionIconTintDefaultsToCaptionForeground() {
        // The slot's default keeps the addition non-breaking: a caller that omits
        // captionIconTint gets a monochrome caption matching captionForeground. This
        // pins that default so a later change to it is a deliberate, reviewed edit.
        val foreground = ArgbColor(0xFF123456.toInt())
        val other = ArgbColor(0xFF000000.toInt())
        val semantics = DocumentSemantics(
            captionBackground = other,
            captionForeground = foreground,
            tileBackground = other,
            tileForeground = other,
            tileLabelForeground = other,
            laneBackground = other,
            documentBadgeBackground = other,
            documentBadgeForeground = other,
        )
        assertThat(semantics.captionIconTint).isEqualTo(foreground)
    }

    /**
     * Bytecode scan that fails closed if any compiled `is.walt.passes.pdf.ui.*` class
     * carries a string constant that would let a contributor wire a Share / Export
     * action or a PDF-MIME callsite. ADR 0005 D8 (no share-out) and D4 (no PDF
     * extraction surface) are the policies; this test is the structural lock on the
     * document-UI side. The PKPASS side has the same scan in `passes-ui`.
     *
     * The needles are scanned as raw UTF-8 byte sequences inside the .class files,
     * which surfaces both Kotlin string literals and Java reflection fragments. A
     * legitimate use (none today) would have to deliberately update the allow-list
     * in the test, making the security-policy edit auditable.
     */
    @Test
    fun passesPdfUiCompiledClassesContainNoForbiddenStrings() {
        val classFiles = classFilesUnder("is/walt/passes/pdf/ui")
        assertThat(classFiles).isNotEmpty()

        val forbidden = listOf(
            "android.intent.action.SEND" to "Intent.ACTION_SEND",
            "android.intent.action.SEND_MULTIPLE" to "Intent.ACTION_SEND_MULTIPLE",
            "application/pdf" to "PDF MIME literal",
        )
        for (file in classFiles) {
            // The test class itself embeds the needle byte sequences in order to
            // search for them. Skip its own .class files (and any inner-class
            // companions Kotlin emits beside it) to avoid a self-trip.
            if (file.name.startsWith("DocumentPublicApiSurfaceTest")) continue
            val bytes = file.readBytes()
            for ((needle, label) in forbidden) {
                val needleBytes = needle.toByteArray(Charsets.UTF_8)
                val index = indexOf(bytes, needleBytes)
                if (index >= 0) {
                    error(
                        "Forbidden $label string '$needle' found at offset $index in " +
                            "${file.absolutePath}. ADR 0005 D8 forbids share-out; ADR " +
                            "0005 D4 forbids PDF MIME / metadata surfaces in passes-pdf-ui. " +
                            "If this addition is intentional, raise it as a security-" +
                            "policy change.",
                    )
                }
            }
        }
    }

    /**
     * Walk every classpath root that exposes [packagePath] and collect the .class
     * files. Using [ClassLoader.getResources] (plural) instead of getResource gets
     * us both the main-classes and test-classes roots in a Gradle test JVM, so the
     * scan covers production code rather than just the first match.
     */
    private fun classFilesUnder(packagePath: String): List<File> {
        val urls = javaClass.classLoader!!.getResources(packagePath).toList()
        return urls.flatMap { url ->
            val file = runCatching { File(url.toURI()) }.getOrNull() ?: return@flatMap emptyList()
            if (!file.isDirectory) return@flatMap emptyList()
            file.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList()
        }
    }

    /**
     * Naive byte-substring search. The needles are short (≤ 30 bytes) and the haystacks
     * are class files in the low-tens-of-kilobytes; an exact-substring KMP would be
     * faster but invisible at this scale. Returning the first match's offset gives the
     * failure message a useful jump-to point.
     */
    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val limit = haystack.size - needle.size
        var found = -1
        outer@ for (i in 0..limit) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            found = i
            break
        }
        return found
    }
}
