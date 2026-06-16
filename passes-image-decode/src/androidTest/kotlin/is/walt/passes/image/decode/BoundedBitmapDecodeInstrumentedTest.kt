package `is`.walt.passes.image.decode

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Instrumented coverage for [decodeBounded] against the REAL platform `ImageDecoder` — the
 * part the JVM suite cannot exercise, since Robolectric's shadow returns a stub bitmap and
 * never decodes, rejects, or throws on malformed input ([BoundedBitmapDecodeTest] explains the
 * split). These pin the native decode-failure containment and the 1x1-on-reject downsize that
 * the module exists to audit: a malformed container folds to the caller's `onMalformed`, an
 * over-cap image is rejected by the gate without a full-size allocation, and the software
 * allocator yields a readable (non-hardware) bitmap.
 *
 * @Ignore'd at check-in: there is no emulator on a workstation, and CI's connected-tests
 * matrix currently covers only `:passes-storage` (see `.github/workflows/ci.yml`), so a
 * `:passes-image-decode` device matrix has to be added before these run. This mirrors
 * `passes-isolation`'s instrumented placeholder and rides with the same on-device CI wiring
 * (wpass-zrt.5). The OOM propagate-vs-contain split is covered deterministically off-device by
 * [BoundedBitmapDecodeTest.containOutOfMemoryPropagatesWhenNoFold] and its siblings, since a
 * real [OutOfMemoryError] cannot be forced reliably.
 */
@RunWith(AndroidJUnit4::class)
class BoundedBitmapDecodeInstrumentedTest {

    private enum class Reason { TooLarge, Malformed, RuntimeFailure, Oom }

    private fun policy(maxSide: Int) =
        BoundedDecodePolicy(
            allocator = ImageDecoder.ALLOCATOR_SOFTWARE,
            gate = { _, w, h -> if (w > maxSide || h > maxSide) Reason.TooLarge else null },
            onMalformed = { Reason.Malformed },
            onRuntimeFailure = { Reason.RuntimeFailure },
            onOutOfMemory = { Reason.Oom },
        )

    @Test
    @Ignore("Pending on-device CI wiring")
    fun decodesValidImageWithinCaps() {
        val result = decodeBounded(rawBytes = pngOf(width = 16, height = 16), policy = policy(maxSide = 64))
        assertThat(result).isInstanceOf(BoundedBitmap.Decoded::class.java)
        val bitmap = (result as BoundedBitmap.Decoded).bitmap
        assertThat(bitmap.width).isEqualTo(16)
        assertThat(bitmap.height).isEqualTo(16)
        // Software allocator: a readable, non-hardware bitmap (a symbol decode needs getPixels).
        assertThat(bitmap.config).isNotEqualTo(Bitmap.Config.HARDWARE)
    }

    @Test
    @Ignore("Pending on-device CI wiring")
    fun rejectsOverCapImageByGateWithoutFullAllocation() {
        // A 16x16 image under a 1px cap trips the gate; the listener forces a 1x1 decode so the
        // rejected path allocates nothing of size.
        val result = decodeBounded(rawBytes = pngOf(width = 16, height = 16), policy = policy(maxSide = 1))
        assertThat(result).isEqualTo(BoundedBitmap.Rejected(Reason.TooLarge))
    }

    @Test
    @Ignore("Pending on-device CI wiring")
    fun foldsMalformedContainerToOnMalformed() {
        // Random bytes are not a decodable container; with no gate rejection in flight the
        // thrown failure folds to the caller's onMalformed value.
        val garbage = ByteArray(256) { (it * 37 + 13).toByte() }
        val result = decodeBounded(rawBytes = garbage, policy = policy(maxSide = 64))
        assertThat(result).isEqualTo(BoundedBitmap.Rejected(Reason.Malformed))
    }

    private fun pngOf(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}
