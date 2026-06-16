package `is`.walt.passes.image.android

import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Behavioural coverage for [doDecode], the service-side orchestration that wraps the bounded
 * raster decode under the watchdog and contains every failure. The bounded-decode step is
 * injected so these run without the platform `ImageDecoder`; what they pin is the contract
 * around it, mirroring `passes-barcode`'s `BarcodeDecodeServiceTest`:
 *
 *  - a bounded-decode rejection passes through unchanged;
 *  - a produced raster passes through unchanged;
 *  - a Throwable from the bounded decode is contained as `Rejected(DecodeFailed)` rather than
 *    escaping the sandbox;
 *  - the source descriptor is closed on every outcome;
 *  - the real fd glue ([decodeRasterFromPfd]) rejects an over-cap source and an out-of-bounds
 *    output request without closing the source PFD (the dup idiom prevents a double-close).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImageDecodeServiceTest {
    private val config = ImageDecodeConfig()

    // Long timeout + recording killer: the fast test blocks never trip it, and it cannot take
    // down the test JVM if they ever did.
    private val watchdog = DecodeWatchdog(timeoutMs = 60_000L, killer = NoopKiller())

    @Test
    fun boundedRejectionPassesThroughAndClosesPfd() = runTest {
        val pfd = TrackingPfd(pipeReadEnd())

        val result =
            doDecode(pfd, MAX_W, MAX_H, config, watchdog) { _, _, _, _ ->
                ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DimensionsTooLarge)
            }

        assertThat(result).isEqualTo(ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DimensionsTooLarge))
        assertThat(pfd.closed).isTrue()
    }

    @Test
    fun producedRasterPassesThroughAndClosesPfd() = runTest {
        val pfd = TrackingPfd(pipeReadEnd())
        val sm = SharedMemory.create("walt-test-svc", MAX_W * MAX_H * 4)
        val ok = ImageDecodeResult.Ok(sm, MAX_W, MAX_H, 1f)

        val result = doDecode(pfd, MAX_W, MAX_H, config, watchdog) { _, _, _, _ -> ok }

        assertThat(result).isSameInstanceAs(ok)
        assertThat(pfd.closed).isTrue()
    }

    @Test
    fun boundedDecodeThrowIsContainedAndPfdClosed() = runTest {
        val pfd = TrackingPfd(pipeReadEnd())

        val result =
            doDecode(pfd, MAX_W, MAX_H, config, watchdog) { _, _, _, _ ->
                error("codec blew up")
            }

        assertThat(result).isEqualTo(ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecodeFailed))
        assertThat(pfd.closed).isTrue()
    }

    @Test
    fun decodeRasterFromPfdRejectsOverSizeWithoutClosingSourcePfd() {
        // Drives the real fd glue (dup + AutoCloseInputStream) the orchestration tests stub
        // out. A tiny size cap trips OversizedAtImport before any ImageDecoder work, and the
        // source PFD must survive — the read closes only its dup, leaving the single close to
        // doDecode.
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(ByteArray(64)) }

        val result = decodeRasterFromPfd(readEnd, MAX_W, MAX_H, ImageDecodeConfig(maxBytes = 4))

        assertThat(result).isEqualTo(ImageDecodeResult.Rejected(ImageDecodeRejectedKind.OversizedAtImport))
        assertThat(readEnd.fileDescriptor.valid()).isTrue()
        readEnd.close()
    }

    @Test
    fun decodeRasterFromPfdRejectsOutOfBoundsOutputRequestBeforeReading() {
        // A caller asking for an over-cap output raster is refused up front — before the source
        // is even read — and the source PFD survives.
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        runCatching { pipe[1].close() }

        val over = 3000
        val result = decodeRasterFromPfd(readEnd, over, over, config)

        assertThat(result).isEqualTo(ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecodeFailed))
        assertThat(readEnd.fileDescriptor.valid()).isTrue()
        readEnd.close()
    }

    // --------------------------------------------------------------------- helpers

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

    private companion object {
        const val MAX_W = 64
        const val MAX_H = 64
    }
}
