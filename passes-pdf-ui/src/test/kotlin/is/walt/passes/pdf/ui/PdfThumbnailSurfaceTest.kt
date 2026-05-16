package `is`.walt.passes.pdf.ui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import `is`.walt.passes.pdf.DocumentRejectedKind
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JvmModifier

/**
 * Locks the public API shape of the PDF thumbnail facade (`wpass-5g7`). The shape IS the
 * trust contract: the only way a future contributor could leak PDF text, metadata, or
 * annotations through this surface is to add a field to [PdfThumbnailState] or a method
 * to [PdfThumbnailCache] — both of which break a test here.
 *
 * The bytecode-scan in [DocumentPublicApiSurfaceTest] covers `application/pdf` MIME and
 * `Intent.ACTION_SEND` literals across the same package, so this test focuses on the
 * structural shape of the new public symbols.
 */
class PdfThumbnailSurfaceTest {

    @Test
    fun rememberPdfThumbnailHasExactlySevenUserVisibleParameters() {
        // (document, pdfFile, renderer, targetSizePx, page, telemetry, cache). Adding
        // an eighth parameter is a public-API change; review wpass-5g7's surface
        // contract before bumping this number.
        val method = findTopLevel("PdfThumbnailKt", "rememberPdfThumbnail")
        val actual = userVisibleParameterCount(method)
        assertWithMessage(
            "rememberPdfThumbnail user-visible parameter count drifted; the shape is " +
                "the public contract (wpass-5g7). Full method signature: $method",
        )
            .that(actual)
            .isEqualTo(7)
    }

    @Test
    fun rememberPdfThumbnailReturnsPdfThumbnailState() {
        val method = findTopLevel("PdfThumbnailKt", "rememberPdfThumbnail")
        assertThat(method.returnType.name).isEqualTo("is.walt.passes.pdf.ui.PdfThumbnailState")
    }

    @Test
    fun pdfThumbnailStateHasExactlyThreePermittedSubclasses() {
        // Loading (data object), Rendered (image + pageAspect), Failed (kind). A fourth
        // arm is a deliberate trust-shape change: a `Pending`, `Cancelled`, or
        // `Stale` arm would force every consumer to update its `when` and would
        // surface a new field that could carry a PDF-extraction-shaped payload.
        val sealed = Class.forName("is.walt.passes.pdf.ui.PdfThumbnailState")
        val permitted = sealed.permittedSubclasses
        assertThat(permitted).isNotNull()
        val names = permitted!!.map { it.simpleName }.toSet()
        assertThat(names).containsExactly("Loading", "Rendered", "Failed")
    }

    @Test
    fun pdfThumbnailStateRenderedExposesOnlyImageAndPageAspect() {
        // Rendered carries exactly two fields: the rasterised bitmap and the source
        // page's natural aspect ratio. No filename, no page index, no metadata map.
        // Adding a field is a security-policy change: any reference type added here
        // could become a path through which PDF text or metadata reaches the consumer.
        val rendered = Class.forName("is.walt.passes.pdf.ui.PdfThumbnailState\$Rendered")
        val publicGetters = rendered.declaredMethods
            .filter { JvmModifier.isPublic(it.modifiers) }
            .filter { it.name.startsWith("get") || it.name.startsWith("component") }
            .map { it.name }
            .toSet()
        // getImage + getPageAspect from the data class; component1/component2 from
        // data-class destructuring. Anything else is a new field.
        assertThat(publicGetters).containsAtLeast("getImage", "getPageAspect")
        val unexpected = publicGetters.filterNot {
            it == "getImage" || it == "getPageAspect" ||
                it == "component1" || it == "component2"
        }
        assertWithMessage(
            "PdfThumbnailState.Rendered has unexpected accessors $unexpected — " +
                "adding a field is a security-policy change (ADR 0005 D4).",
        ).that(unexpected).isEmpty()
    }

    @Test
    fun pdfThumbnailStateFailedKindIsDocumentRejectedKind() {
        // Failed carries a single enum, the same vocabulary the renderer service uses
        // for rejections. A String / Throwable / message field would be a telemetry
        // PII leak by the same rule that DocumentTelemetryGuard enforces.
        val failed = Class.forName("is.walt.passes.pdf.ui.PdfThumbnailState\$Failed")
        val getter = failed.declaredMethods.single { it.name == "getKind" }
        assertThat(getter.returnType).isEqualTo(DocumentRejectedKind::class.java)
    }

    @Test
    fun pdfThumbnailCachePublicSurfaceIsConstructorAndClear() {
        // The cache is a thin RAM-bound LRU. Adding a `peek`, `entries`, `keys`, or
        // `toMap` method would let a consumer extract page bitmaps out of band of the
        // composable that owns them — an ownership-laundering surface that should not
        // exist on this type. Kotlin `internal` members are JVM-public with a mangled
        // `name$module` suffix; filter them out — they're inaccessible from Kotlin
        // consumers outside the module by Kotlin's visibility rules.
        val klass = Class.forName("is.walt.passes.pdf.ui.PdfThumbnailCache")
        val declared = klass.declaredMethods
            .filter { JvmModifier.isPublic(it.modifiers) && !it.isSynthetic }
            .filterNot { it.name.contains('$') }
            .map { it.name }
            .toSet()
        assertWithMessage("PdfThumbnailCache public method surface drifted")
            .that(declared)
            .containsExactly("clear")
    }

    @Test
    fun newPublicTypesHaveNoExtractionShapedAccessors() {
        // Defense-in-depth on top of the bytecode-scan in DocumentPublicApiSurfaceTest:
        // even if a future field's bytecode does not include the literal "application/
        // pdf" or "android.intent.action.SEND", a method named `getText`, `getMetadata`,
        // `getAnnotations`, `getAttachments`, or similar is a structural signal that
        // someone is wiring an extraction path into the trust-bearing surface.
        val forbidden = setOf(
            "getText", "getMetadata", "getAnnotations", "getAttachments",
            "getFormFields", "getJavaScript", "getBookmarks", "getOutline",
        )
        val classesToScan = listOf(
            "is.walt.passes.pdf.ui.PdfThumbnailKt",
            "is.walt.passes.pdf.ui.PdfThumbnailState",
            "is.walt.passes.pdf.ui.PdfThumbnailState\$Loading",
            "is.walt.passes.pdf.ui.PdfThumbnailState\$Rendered",
            "is.walt.passes.pdf.ui.PdfThumbnailState\$Failed",
            "is.walt.passes.pdf.ui.PdfThumbnailCache",
        )
        for (className in classesToScan) {
            val klass = Class.forName(className)
            val offenders = klass.methods.map { it.name }.intersect(forbidden)
            assertWithMessage("$className exposes extraction-shaped accessors $offenders")
                .that(offenders)
                .isEmpty()
        }
    }

    // -- helpers (mirroring DocumentSurfaceLockTest) -------------------------------

    private fun findTopLevel(fileClassSimpleName: String, methodName: String): Method {
        val klass = Class.forName("is.walt.passes.pdf.ui.$fileClassSimpleName")
        val matches = klass.methods.filter {
            it.name == methodName || it.name.startsWith("$methodName-")
        }
        assertWithMessage(
            "Expected exactly one $fileClassSimpleName.$methodName method; a surprise " +
                "overload should fail loudly so the surface lock catches the addition.",
        ).that(matches).hasSize(1)
        return matches.single()
    }

    // Compose-compiler-dependent: synthetic `Composer` + `int $changed` trailing
    // parameters are stripped. The exact mangling is an implementation detail of the
    // current Compose compiler; if it changes, this helper needs to follow.
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
}
