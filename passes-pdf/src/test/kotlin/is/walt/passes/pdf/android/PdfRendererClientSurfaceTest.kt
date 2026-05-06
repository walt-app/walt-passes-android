package `is`.walt.passes.pdf.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the public surface of [PdfRendererClient] to the exact [PdfRendererBinder]
 * contract. The interface-level lock in [PublicApiSurfaceTest] is the central trust
 * claim of ADR 0005 D4 (no extraction); this test is its companion at the *class* level
 * so a contributor cannot quietly grow the client with a passthrough helper
 * (`getText(pfd)`, `extract(pfd)`, etc) that does not appear on the interface and
 * therefore would not have tripped the interface surface test.
 *
 * Allowlist over denylist for the same reason as the interface test: a denylist test
 * leaks past synonyms (`extractText`, `text`, `documentText`); allowlisting the
 * exactly-two-methods-by-name closes the gap structurally.
 */
class PdfRendererClientSurfaceTest {
    @Test
    fun clientHasExactlyProbeAndRender() {
        val methodNames =
            PdfRendererClient::class
                .java
                .declaredMethods
                .map { it.name }
                .toSet()
        assertThat(methodNames).containsExactly("probe", "render")
    }

    @Test
    fun clientImplementsBinderContract() {
        val interfaces = PdfRendererClient::class.java.interfaces.map { it.name }.toSet()
        assertThat(interfaces).contains(PdfRendererBinder::class.java.name)
    }

    @Test
    fun clientHasNoExtractionSurface() {
        val forbidden =
            setOf(
                "getText",
                "getMetadata",
                "getAnnotations",
                "getAttachments",
                "getFormFields",
                "extractText",
                "extractMetadata",
                "text",
                "metadata",
            )
        val methodNames = PdfRendererClient::class.java.declaredMethods.map { it.name }.toSet()
        assertThat(methodNames.intersect(forbidden)).isEmpty()
    }
}
