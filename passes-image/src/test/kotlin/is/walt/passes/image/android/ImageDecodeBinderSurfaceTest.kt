package `is`.walt.passes.image.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Locks the decode binder surface to exactly [ImageDecodeBinder.decode]. The deliberate
 * absence of any extraction surface — no `decodeToBitmap`, no `lastImage`, no raw-bytes
 * accessor, no metadata getter — is the central trust claim of wpass-6yp: the isolated decode
 * service returns only a bounded, Walt-produced raster and never the hostile source image. A
 * reflection lock makes that structural rather than aspirational, mirroring `passes-barcode`'s
 * `BarcodeDecodeBinderSurfaceTest` and `passes-pdf`'s `PdfRendererClientSurfaceTest`.
 */
class ImageDecodeBinderSurfaceTest {
    @Test
    fun binderHasExactlyDecode() {
        // Filter the mangled `$default` suffix Kotlin generates for default-valued params so
        // adding a default to `decode` would not flip the test, but adding a second method
        // would.
        val methodNames =
            ImageDecodeBinder::class
                .java
                .declaredMethods
                .map { it.name.substringBefore('$') }
                .toSet()
        assertThat(methodNames).containsExactly("decode")
    }

    @Test
    fun decodeTakesPfdAndBoundsAndIsSuspend() {
        val decode = ImageDecodeBinder::class.java.declaredMethods.single { it.name == "decode" }
        val params = decode.parameterTypes
        // (pfd, maxWidthPx, maxHeightPx) + the suspend Continuation tail. Its presence proves
        // no synchronous side-channel return shape leaks.
        assertThat(params).hasLength(4)
        assertThat(params[0].name).isEqualTo("android.os.ParcelFileDescriptor")
        assertThat(params[1].name).isEqualTo("int")
        assertThat(params[2].name).isEqualTo("int")
        assertThat(params[3].name).isEqualTo("kotlin.coroutines.Continuation")
    }

    @Test
    fun clientHasExactlyDecode() {
        // Restrict to the callable surface: public, non-synthetic methods. A contributor
        // adding an extraction backdoor would have to make it public to be useful, so checking
        // the public surface preserves the trust claim. Private helpers are implementation
        // detail.
        val methodNames =
            ImageDecodeClient::class
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
        val interfaces = ImageDecodeClient::class.java.interfaces.map { it.name }.toSet()
        assertThat(interfaces).contains(ImageDecodeBinder::class.java.name)
    }
}
