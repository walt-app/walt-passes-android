package `is`.walt.passes.barcode.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Locks the decode binder surface to exactly [BarcodeDecodeBinder.decode]. The deliberate
 * absence of any extraction surface — no `decodeToBitmap`, no `lastImage`, no raw-bytes
 * accessor, no metadata getter — is the central trust claim of wpass-zrt: the isolated
 * decode service returns only `{payload, format}` and never the hostile image. A reflection
 * lock makes that structural rather than aspirational, mirroring `passes-pdf`'s
 * `PublicApiSurfaceTest` / `PdfRendererClientSurfaceTest`.
 */
class BarcodeDecodeBinderSurfaceTest {
    @Test
    fun binderHasExactlyDecode() {
        // Filter the mangled `$default` suffix Kotlin generates for default-valued params
        // so adding a default to `decode` would not flip the test, but adding a second
        // method would.
        val methodNames =
            BarcodeDecodeBinder::class
                .java
                .declaredMethods
                .map { it.name.substringBefore('$') }
                .toSet()
        assertThat(methodNames).containsExactly("decode")
    }

    @Test
    fun decodeTakesPfdAndIsSuspend() {
        val decode = BarcodeDecodeBinder::class.java.declaredMethods.single { it.name == "decode" }
        val params = decode.parameterTypes
        // suspend fun adds the Continuation tail; its presence proves no synchronous
        // side-channel return shape leaks.
        assertThat(params).hasLength(2)
        assertThat(params[0].name).isEqualTo("android.os.ParcelFileDescriptor")
        assertThat(params[1].name).isEqualTo("kotlin.coroutines.Continuation")
    }

    @Test
    fun clientHasExactlyDecode() {
        // Restrict to the callable surface: public, non-synthetic methods. A contributor
        // adding an extraction backdoor would have to make it public to be useful, so
        // checking the public surface preserves the trust claim. Private helpers (e.g.
        // `decoderUnavailable`) are implementation detail.
        val methodNames =
            BarcodeDecodeClient::class
                .java
                .declaredMethods
                .filter { Modifier.isPublic(it.modifiers) }
                .filterNot { it.isSynthetic }
                .map { it.name }
                .toSet()
        assertThat(methodNames).containsExactly("decode")
    }

    @Test
    fun clientImplementsBinderContract() {
        val interfaces = BarcodeDecodeClient::class.java.interfaces.map { it.name }.toSet()
        assertThat(interfaces).contains(BarcodeDecodeBinder::class.java.name)
    }
}
