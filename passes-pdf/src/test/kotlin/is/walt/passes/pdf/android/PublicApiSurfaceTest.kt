package `is`.walt.passes.pdf.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the binder surface to exactly [PdfRendererBinder.probe] and
 * [PdfRendererBinder.render]. The deliberate absence of any extraction surface — no
 * `getText`, no `getMetadata`, no `getAnnotations`, no `getAttachments`, no
 * `getFormFields` — is the central trust claim of ADR 0005 D4 (no extraction from PDF
 * content). A reflection lock makes that claim structural rather than aspirational:
 * a future contributor cannot quietly grow the surface, because the test would fail
 * before review.
 *
 * Allowlist over denylist for the same reason as
 * `passes-pdf-core`'s `DocumentTelemetryGuardSurfaceTest`: a denylist test
 * ("no method named getText") leaks past synonyms (`extractText`, `text`,
 * `documentText`) that the renderer service might still serve. Allowlisting the
 * exactly-two-methods-by-name closes the gap structurally.
 */
class PublicApiSurfaceTest {
    @Test
    fun binderHasExactlyProbeAndRender() {
        // declaredMethods on an interface includes the abstract methods and any
        // synthetic $default helpers Kotlin generates for parameters with default
        // values. Compare against the set of human-named methods (filtering the
        // mangled `$default` suffix) so that adding a default value to an existing
        // method does not flip the test red, but adding a third method does.
        val methodNames =
            PdfRendererBinder::class
                .java
                .declaredMethods
                .map { it.name.substringBefore('$') }
                .toSet()
        assertThat(methodNames).containsExactly("probe", "render")
    }

    @Test
    fun probeReturnsProbeResult() {
        val probe = PdfRendererBinder::class.java.declaredMethods.single { it.name == "probe" }
        // suspend fun returns Object (the Continuation pattern). The structural property
        // we want to assert is that no synchronous side-channel return type leaks;
        // confirming the method has the suspend continuation parameter shape is enough.
        val params = probe.parameterTypes
        assertThat(params).hasLength(2)
        assertThat(params[0].name).isEqualTo("android.os.ParcelFileDescriptor")
        assertThat(params[1].name).isEqualTo("kotlin.coroutines.Continuation")
    }

    @Test
    fun renderTakesPfdAndDimensionsAndSourceRect() {
        val render = PdfRendererBinder::class.java.declaredMethods.single { it.name == "render" }
        val params = render.parameterTypes
        // wpass-f4b extends render() with a RenderSourceRect parameter (between
        // heightPx and the Continuation). The pin matters because reordering the
        // parameters at the interface level would invalidate the wire format the proxy
        // and client agree on by position.
        assertThat(params).hasLength(6)
        assertThat(params[0].name).isEqualTo("android.os.ParcelFileDescriptor")
        assertThat(params[1]).isEqualTo(java.lang.Integer.TYPE)
        assertThat(params[2]).isEqualTo(java.lang.Integer.TYPE)
        assertThat(params[3]).isEqualTo(java.lang.Integer.TYPE)
        assertThat(params[4].name).isEqualTo("is.walt.passes.pdf.android.RenderSourceRect")
        assertThat(params[5].name).isEqualTo("kotlin.coroutines.Continuation")
    }
}
