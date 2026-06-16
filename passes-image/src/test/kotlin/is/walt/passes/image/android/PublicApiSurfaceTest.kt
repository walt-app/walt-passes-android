package `is`.walt.passes.image.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the public surface of the image-decode module (wpass-6yp), mirroring the allowlist
 * discipline of `passes-barcode`'s `PublicApiSurfaceTest`. The facade's entire reason to exist
 * is to be the *only* convenient decode entry point; a reflection lock makes "exactly one
 * decode method, returning only a bounded raster or a rejection" structural rather than
 * aspirational, so a future contributor cannot quietly grow the surface (a `decodeToBitmap`, a
 * `lastImage`, a raw-bytes accessor) without tripping a test before review.
 *
 * Also pins that the reject taxonomy is its OWN type, deliberately NOT
 * `passes-pdf-core`'s `DocumentRejectedKind` (the wpass-i9x acceptance criterion).
 *
 * Pure-JVM by construction: it reflects on shapes and exercises the pure result arms, and
 * never constructs the Android-typed [ImageSource] arms, so it needs no Robolectric.
 */
class PublicApiSurfaceTest {
    @Test
    fun decoderHasExactlyDecode() {
        val methodNames =
            BoundedImageDecoder::class
                .java
                .declaredMethods
                .map { it.name.substringBefore('$') }
                .toSet()
        assertThat(methodNames).containsExactly("decode")
    }

    @Test
    fun decodeTakesSourceAndBoundsAndIsSuspend() {
        val decode = BoundedImageDecoder::class.java.declaredMethods.single { it.name == "decode" }
        val params = decode.parameterTypes
        // (source, maxWidthPx, maxHeightPx) + the suspend Continuation tail; its presence
        // proves no synchronous side-channel return shape leaks.
        assertThat(params).hasLength(4)
        assertThat(params[0].name).isEqualTo("is.walt.passes.image.android.ImageSource")
        assertThat(params[1].name).isEqualTo("int")
        assertThat(params[2].name).isEqualTo("int")
        assertThat(params[3].name).isEqualTo("kotlin.coroutines.Continuation")
    }

    @Test
    fun createTakesContextAndReturnsDecoder() {
        val create =
            BoundedImageDecoder.Companion::class.java.declaredMethods.single { it.name == "create" }
        assertThat(create.parameterTypes.map { it.name })
            .containsExactly("android.content.Context")
        assertThat(create.returnType).isEqualTo(BoundedImageDecoder::class.java)
    }

    @Test
    fun imageSourceHasExactlyTwoArms() {
        val arms = ImageSource::class.java.declaredClasses.map { it.simpleName }.toSet()
        assertThat(arms).containsExactly("ContentUri", "FileDescriptor")
    }

    @Test
    fun decodeResultArmsAreReachableViaWhen() {
        val results: List<ImageDecodeResult> =
            listOf(
                ImageDecodeResult.Rejected(ImageDecodeRejectedKind.NotAnImage),
            )
        val branches =
            results.map { result ->
                when (result) {
                    is ImageDecodeResult.Ok -> "ok"
                    is ImageDecodeResult.Rejected -> "rejected"
                }
            }
        assertThat(branches).containsExactly("rejected")
    }

    @Test
    fun okArmReportsOnlyRasterHandleDimensionsAndAspect() {
        // The success arm carries exactly the four fields the trust claim permits — a
        // SharedMemory raster handle, its width/height, and the source aspect. No source
        // bytes, no caller-supplied Bitmap. Adding a fifth constructor parameter trips this.
        val componentNames =
            ImageDecodeResult.Ok::class
                .java
                .declaredMethods
                .map { it.name }
                .filter { it.startsWith("component") }
        assertThat(componentNames).hasSize(4)

        val okParamTypes =
            ImageDecodeResult.Ok::class.java.declaredConstructors
                .single()
                .parameterTypes
                .map { it.name }
        assertThat(okParamTypes)
            .containsExactly("android.os.SharedMemory", "int", "int", "float")
            .inOrder()
    }

    @Test
    fun rejectedKindArmsArePinned() {
        // The arms are declared as nested objects of the sealed interface; reflect on the
        // declared classes (Java reflection, not kotlin-reflect, which is off the test
        // classpath) rather than the JDK-17 permittedSubclasses attribute.
        val arms = ImageDecodeRejectedKind::class.java.declaredClasses.map { it.simpleName }.toSet()
        assertThat(arms)
            .containsExactly(
                "NotAnImage",
                "OversizedAtImport",
                "DimensionsTooLarge",
                "DecodeFailed",
                "DecoderUnavailable",
            )
    }

    @Test
    fun rejectedKindIsItsOwnTypeNotThePdfDocumentRejectedKind() {
        // wpass-i9x acceptance: the image reject taxonomy must NOT be flattened into the PDF
        // DocumentRejectedKind. Pin that they are distinct types in distinct packages.
        assertThat(ImageDecodeRejectedKind::class.java.name)
            .isEqualTo("is.walt.passes.image.android.ImageDecodeRejectedKind")
        assertThat(ImageDecodeRejectedKind::class.java.name)
            .isNotEqualTo("is.walt.passes.pdf.DocumentRejectedKind")
    }
}
