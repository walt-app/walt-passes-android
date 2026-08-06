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
 *
 * Also covers [warmDecodePath], the `onCreate` warm-up that keeps cold start out of the
 * watchdog budget (wpass-qw3).
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
            doDecode(pfd, config, watchdog, { BarcodeDecodeResult.NoBarcodeFound }) { _, _ ->
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

    @Test
    fun decodeBoundedFromPfdRejectsOverSizeWithoutClosingSourcePfd() {
        // Drives the real fd glue (dup + AutoCloseInputStream) the orchestration tests stub
        // out. A tiny size cap trips ImageTooLarge before any ImageDecoder work, and the
        // source PFD must survive — the read closes only its dup, leaving the single close to
        // doDecode. Regresses the double-close footgun the dup idiom prevents.
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(ByteArray(64)) }

        val result = decodeBoundedFromPfd(readEnd, BarcodeDecodeConfig(maxBytes = 4))

        assertThat(result).isEqualTo(BoundedDecodeResult.Rejected(DecodeFailureReason.ImageTooLarge))
        assertThat(readEnd.fileDescriptor.valid()).isTrue()
        readEnd.close()
    }

    @Test
    fun warmDecodePathRunsTheSymbolDecoderOffTheDecodeBudget() {
        // The warm-up must actually touch ZXing — a no-op would leave the reader classes to
        // load inside the watchdog budget, which is the wpass-qw3 failure.
        var probedWidth = 0
        var probedHeight = 0

        warmDecodePath(config) { bitmap ->
            probedWidth = bitmap.width
            probedHeight = bitmap.height
            BarcodeDecodeResult.NoBarcodeFound
        }

        // Both stay 0 if the decoder was never called; 40 is ZXing's hybrid-binarizer floor,
        // below which the warm-up would touch a binarizer path the real decode never uses.
        assertThat(probedWidth).isAtLeast(40)
        assertThat(probedHeight).isAtLeast(40)
    }

    @Test
    fun serviceWatchdogAndClientAttributionReadTheSameBudget() {
        // Host-side timeout attribution is a shared-constant handshake across a process
        // boundary with no wire check, so the value must be pinned on BOTH sides. The client
        // half is pinned in BarcodeDecodeBinderRoundTripTest; this is the service half, which
        // arms its watchdog from the default config rather than the constant directly.
        assertThat(BarcodeDecodeConfig().decodeTimeoutMs)
            .isEqualTo(BarcodeDecodeConfig.DEFAULT_DECODE_TIMEOUT_MS)
    }

    @Test
    fun warmDecodePathContainsFailures() {
        // Warm-up is an optimization. A throw here would take down onCreate and turn a slow
        // decode into no decode at all, so nothing may escape.
        warmDecodePath(config) { error("warm-up blew up") }
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
