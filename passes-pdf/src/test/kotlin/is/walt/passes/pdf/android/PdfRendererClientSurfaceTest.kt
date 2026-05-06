package `is`.walt.passes.pdf.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.lang.reflect.Modifier

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
        // Restrict to the *callable* surface: public, non-synthetic methods. Private
        // helpers (e.g. a Parcel-decoding utility) are implementation details of the
        // contract this test pins, not extensions of the contract itself; a contributor
        // adding a `getText` passthrough would have to make it public to be useful as
        // an extraction backdoor, so the trust claim is preserved by checking only the
        // public surface. Synthetics are filtered for the same reason — Kotlin's
        // `access$<field>$p` accessor (generated when a lambda closes over a private
        // property) is JVM visibility plumbing, not author surface.
        val methodNames =
            PdfRendererClient::class
                .java
                .declaredMethods
                .filter { Modifier.isPublic(it.modifiers) }
                .filterNot { it.isSynthetic }
                .map { it.name }
                .toSet()
        assertThat(methodNames).containsExactly("probe", "render")
    }

    @Test
    fun clientImplementsBinderContract() {
        val interfaces = PdfRendererClient::class.java.interfaces.map { it.name }.toSet()
        assertThat(interfaces).contains(PdfRendererBinder::class.java.name)
    }
}
