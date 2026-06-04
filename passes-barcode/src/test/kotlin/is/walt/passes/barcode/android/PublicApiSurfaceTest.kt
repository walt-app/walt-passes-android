package `is`.walt.passes.barcode.android

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.DecodeFailureReason
import `is`.walt.passes.core.ScannableFormat
import org.junit.Test

/**
 * Locks the public surface of the barcode decode facade (wpass-zrt.1), mirroring the
 * allowlist discipline of `passes-pdf`'s `PublicApiSurfaceTest` and
 * `ScannableCardSurfaceTest`. The facade's entire reason to exist is to be the *only* decode
 * entry point; a reflection lock makes "exactly one decode method, returning only
 * {payload, format}" structural rather than aspirational, so a future contributor cannot
 * quietly grow the surface (e.g. a `decodeToBitmap`, a `lastImage`, a raw-bytes accessor)
 * without tripping a test before review.
 *
 * Pure-JVM by construction: it reflects on shapes and exercises the pure result arms, and
 * never constructs the Android-typed [BarcodeImageSource] arms, so it needs no Robolectric.
 */
class PublicApiSurfaceTest {
    @Test
    fun decoderHasExactlyDecode() {
        val methodNames =
            BarcodeImageDecoder::class
                .java
                .declaredMethods
                .map { it.name.substringBefore('$') }
                .toSet()
        assertThat(methodNames).containsExactly("decode")
    }

    @Test
    fun decodeTakesImageSourceAndIsSuspend() {
        val decode =
            BarcodeImageDecoder::class.java.declaredMethods.single { it.name == "decode" }
        val params = decode.parameterTypes
        assertThat(params).hasLength(2)
        assertThat(params[0].name).isEqualTo("is.walt.passes.barcode.android.BarcodeImageSource")
        // suspend fun adds the Continuation tail; its presence is what proves no synchronous
        // side-channel return shape leaks.
        assertThat(params[1].name).isEqualTo("kotlin.coroutines.Continuation")
    }

    @Test
    fun createTakesContextAndReturnsDecoder() {
        val create =
            BarcodeImageDecoder.Companion::class.java.declaredMethods.single { it.name == "create" }
        assertThat(create.parameterTypes.map { it.name })
            .containsExactly("android.content.Context")
        assertThat(create.returnType).isEqualTo(BarcodeImageDecoder::class.java)
    }

    @Test
    fun imageSourceHasExactlyTwoArms() {
        // Java reflection (not kotlin-reflect, which is off the test classpath): the two
        // arms are declared as nested classes of the sealed interface.
        val arms = BarcodeImageSource::class.java.declaredClasses.map { it.simpleName }.toSet()
        assertThat(arms).containsExactly("ContentUri", "FileDescriptor")
    }

    @Test
    fun decodeResultArmsAreReachableViaWhen() {
        val results: List<BarcodeDecodeResult> =
            listOf(
                BarcodeDecodeResult.DecodedBarcode(payload = "X", format = ScannableFormat.Qr),
                BarcodeDecodeResult.NoBarcodeFound,
                BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.DecoderUnavailable),
            )
        val branches =
            results.map { result ->
                when (result) {
                    is BarcodeDecodeResult.DecodedBarcode -> "decoded"
                    is BarcodeDecodeResult.NoBarcodeFound -> "none"
                    is BarcodeDecodeResult.DecodeFailed -> "failed"
                }
            }
        assertThat(branches).containsExactly("decoded", "none", "failed").inOrder()
    }

    @Test
    fun decodedBarcodeReportsOnlyPayloadAndFormat() {
        // The success arm carries exactly the two fields the trust claim permits — no Bitmap,
        // no source bytes. Adding a third constructor parameter trips this lock.
        val componentNames =
            BarcodeDecodeResult.DecodedBarcode::class
                .java
                .declaredMethods
                .map { it.name }
                .filter { it.startsWith("component") }
        assertThat(componentNames).hasSize(2)

        val decoded = BarcodeDecodeResult.DecodedBarcode(payload = "PASS", format = ScannableFormat.Code128)
        assertThat(decoded.payload).isEqualTo("PASS")
        assertThat(decoded.format).isEqualTo(ScannableFormat.Code128)
    }

    @Test
    fun decodeFailureReasonRosterIsPinned() {
        assertThat(DecodeFailureReason.entries.map { it.name })
            .containsExactly(
                "SourceUnreadable",
                "ImageDecodeFailed",
                "ImageTooLarge",
                "UnsupportedBarcodeFormat",
                "DecoderUnavailable",
            )
    }
}
