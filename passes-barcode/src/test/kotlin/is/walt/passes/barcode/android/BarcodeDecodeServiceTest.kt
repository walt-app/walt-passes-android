package `is`.walt.passes.barcode.android

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.DecodeFailureReason
import `is`.walt.passes.core.ScannableFormat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Behavioural coverage for [doDecode], the service-side orchestration that composes the
 * bounded codec decode (wpass-zrt.3) with the symbol decode (wpass-zrt.4). The bounded-decode
 * step is injected so these run without the platform `ImageDecoder`; what they pin is the
 * contract around it:
 *
 *  - a bounded-decode rejection maps to `DecodeFailed` with the same reason;
 *  - a decoded bitmap is handed to the symbol decoder, and its result is returned;
 *  - the bitmap is recycled inside the sandbox on every decoded path (it never crosses back);
 *  - a Throwable from either the bounded decode or the symbol decode is contained as
 *    `DecodeFailed(ImageDecodeFailed)` rather than escaping;
 *  - the source descriptor is closed on every outcome.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BarcodeDecodeServiceTest {
    @Test
    fun boundedRejectionMapsToDecodeFailedAndClosesPfd() = runTest {
        val pfd = TrackingPfd(pipeReadEnd())

        val result =
            doDecode(pfd, config, watchdog, neverCalledSymbolDecoder()) { _, _ ->
                BoundedDecodeResult.Rejected(DecodeFailureReason.ImageTooLarge)
            }

        assertThat(result).isEqualTo(BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.ImageTooLarge))
        assertThat(pfd.closed).isTrue()
    }

    @Test
    fun decodedBitmapGoesToSymbolDecoderAndIsRecycled() = runTest {
        val pfd = TrackingPfd(pipeReadEnd())
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val decoded = BarcodeDecodeResult.DecodedBarcode("PASS-9", ScannableFormat.Qr)

        val result =
            doDecode(pfd, config, watchdog, { decoded }) { _, _ ->
                BoundedDecodeResult.Decoded(bitmap)
            }

        assertThat(result).isEqualTo(decoded)
        assertThat(bitmap.isRecycled).isTrue()
        assertThat(pfd.closed).isTrue()
    }

    @Test
    fun cleanDecodeWithNoSymbolReturnsNoBarcodeFound() = runTest {
        val pfd = TrackingPfd(pipeReadEnd())
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        val result =
            doDecode(pfd, config, watchdog, BarcodeSymbolDecoder.NotYetImplemented) { _, _ ->
                BoundedDecodeResult.Decoded(bitmap)
            }

        assertThat(result).isEqualTo(BarcodeDecodeResult.NoBarcodeFound)
        assertThat(bitmap.isRecycled).isTrue()
        assertThat(pfd.closed).isTrue()
    }

    @Test
    fun symbolDecoderThrowIsContainedAndBitmapStillRecycled() = runTest {
        val pfd = TrackingPfd(pipeReadEnd())
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        val result =
            doDecode(pfd, config, watchdog, { error("symbol decode blew up") }) { _, _ ->
                BoundedDecodeResult.Decoded(bitmap)
            }

        assertThat(result).isEqualTo(BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.ImageDecodeFailed))
        assertThat(bitmap.isRecycled).isTrue()
        assertThat(pfd.closed).isTrue()
    }

    @Test
    fun boundedDecodeThrowIsContainedAndPfdClosed() = runTest {
        val pfd = TrackingPfd(pipeReadEnd())

        val result =
            doDecode(pfd, config, watchdog, neverCalledSymbolDecoder()) { _, _ ->
                error("codec blew up")
            }

        assertThat(result).isEqualTo(BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.ImageDecodeFailed))
        assertThat(pfd.closed).isTrue()
    }

    // --------------------------------------------------------------------- helpers

    private val config = BarcodeDecodeConfig()

    // Long timeout + recording killer: the fast test blocks never trip it, and it cannot
    // take down the test JVM if they ever did.
    private val watchdog = DecodeWatchdog(timeoutMs = 60_000L, killer = NoopKiller())

    private fun neverCalledSymbolDecoder(): BarcodeSymbolDecoder =
        BarcodeSymbolDecoder { error("symbol decoder must not be called on a rejected/failed decode") }

    private fun pipeReadEnd(): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        runCatching { pipe[1].close() }
        return pipe[0]
    }

    private class NoopKiller : ProcessKiller {
        override fun killSelf() = Unit
    }

    /** Wraps a real PFD to record whether [doDecode] closed it in its `finally`. */
    private class TrackingPfd(wrapped: ParcelFileDescriptor) : ParcelFileDescriptor(wrapped) {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
