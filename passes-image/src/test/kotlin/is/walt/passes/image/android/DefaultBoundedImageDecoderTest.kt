package `is`.walt.passes.image.android

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.isolation.ConnectResult
import `is`.walt.passes.isolation.IsolatedWorkerSession
import `is`.walt.passes.isolation.IsolatedWorkerSessionFactory
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Behavioural coverage for [DefaultBoundedImageDecoder]. Each test pins one rule the facade
 * promises — mirroring `passes-barcode`'s `DefaultBarcodeImageDecoderTest`, scoped to the image
 * decode orchestration:
 *
 *  - An unreadable / disallowed-scheme source short-circuits to `DecodeFailed` *before* any
 *    bind is attempted.
 *  - A failed bind folds to `DecoderUnavailable`.
 *  - The binder's result rounds back through `decode` unchanged (the wire arms are pinned
 *    separately by [ImageDecodeBinderRoundTripTest]).
 *  - The session is unbound and the source PFD is closed on *every* outcome.
 *
 * The bind and the source open are injected as `Deps` seams, so the orchestration runs without
 * a live isolated process or a real `ContentResolver`. The public surface
 * (`BoundedImageDecoder.create`) stays untouched.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DefaultBoundedImageDecoderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun unreadableSourceShortCircuitsBeforeBinding() = runTest {
        val factory = RecordingSessionFactory()
        val decoder = decoder(sessionFactory = factory, openPfd = { null })

        val result = decoder.decode(fileDescriptorSource(), MAX_W, MAX_H)

        assertThat(result).isEqualTo(ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecodeFailed))
        assertThat(factory.connectCalls).isEqualTo(0)
    }

    @Test
    fun bindFailureFoldsToDecoderUnavailableAndClosesPfd() = runTest {
        val pfd = TrackingPfd(pipeReadEnd())
        val factory = RecordingSessionFactory(connectResult = ConnectResult.BindFailed)
        val decoder = decoder(sessionFactory = factory, openPfd = { pfd })

        val result = decoder.decode(fileDescriptorSource(), MAX_W, MAX_H)

        assertThat(result).isEqualTo(ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecoderUnavailable))
        assertThat(factory.connectCalls).isEqualTo(1)
        assertThat(pfd.closed).isTrue()
    }

    @Test
    fun producedRasterRoundsThroughAndTearsDown() = runTest {
        val pfd = TrackingPfd(pipeReadEnd())
        val sm = SharedMemory.create("walt-test-facade", MAX_W * MAX_H * 4)
        val ok = ImageDecodeResult.Ok(sm, MAX_W, MAX_H, 1f)
        val factory = RecordingSessionFactory(binder = StaticDecodeBinder(ok))
        val decoder = decoder(sessionFactory = factory, openPfd = { pfd })

        val result = decoder.decode(fileDescriptorSource(), MAX_W, MAX_H)

        assertThat(result).isSameInstanceAs(ok)
        assertThat(factory.lastSession?.closed).isTrue()
        assertThat(pfd.closed).isTrue()
    }

    @Test
    fun sessionIsClosedEvenWhenBinderReportsRejection() = runTest {
        val pfd = TrackingPfd(pipeReadEnd())
        val factory =
            RecordingSessionFactory(
                binder = StaticDecodeBinder(ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DimensionsTooLarge)),
            )
        val decoder = decoder(sessionFactory = factory, openPfd = { pfd })

        val result = decoder.decode(fileDescriptorSource(), MAX_W, MAX_H)

        assertThat(result).isEqualTo(ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DimensionsTooLarge))
        assertThat(factory.lastSession?.closed).isTrue()
        assertThat(pfd.closed).isTrue()
    }

    @Test
    fun nonContentSchemeUriIsRejectedByDefaultOpener() {
        // file:// is the canonical escape-hatch shape the scheme allowlist closes:
        // openFileDescriptor would otherwise resolve an arbitrary filesystem path.
        val fileUri = Uri.parse("file:///data/data/example/cache/x.png")
        val source = ImageSource.ContentUri(fileUri, context.contentResolver)
        assertThat(DefaultBoundedImageDecoder.defaultOpenPfd(source)).isNull()
    }

    // --------------------------------------------------------------------- helpers

    private fun decoder(
        sessionFactory: IsolatedWorkerSessionFactory<ImageDecodeBinder>,
        openPfd: (ImageSource) -> ParcelFileDescriptor?,
    ): DefaultBoundedImageDecoder =
        DefaultBoundedImageDecoder(
            appContext = context,
            deps = DefaultBoundedImageDecoder.Deps(
                sessionFactoryFor = { sessionFactory },
                openPfd = openPfd,
            ),
        )

    private fun fileDescriptorSource(): ImageSource =
        ImageSource.FileDescriptor(pipeReadEnd())

    private fun pipeReadEnd(): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        runCatching { pipe[1].close() }
        return pipe[0]
    }

    /** Wraps a real PFD to record whether the decoder closed it in its `finally`. */
    private class TrackingPfd(wrapped: ParcelFileDescriptor) : ParcelFileDescriptor(wrapped) {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class RecordingSessionFactory(
        private val binder: ImageDecodeBinder = StaticDecodeBinder(
            ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecodeFailed),
        ),
        private val connectResult: ConnectResult<ImageDecodeBinder>? = null,
    ) : IsolatedWorkerSessionFactory<ImageDecodeBinder> {
        var connectCalls: Int = 0
        var lastSession: RecordingSession? = null

        override suspend fun connect(): ConnectResult<ImageDecodeBinder> {
            connectCalls++
            connectResult?.let { return it }
            val s = RecordingSession(binder)
            lastSession = s
            return ConnectResult.Connected(s)
        }
    }

    private class RecordingSession(
        override val client: ImageDecodeBinder,
    ) : IsolatedWorkerSession<ImageDecodeBinder> {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
        }
    }

    private class StaticDecodeBinder(
        private val result: ImageDecodeResult,
    ) : ImageDecodeBinder {
        override suspend fun decode(
            image: ParcelFileDescriptor,
            maxWidthPx: Int,
            maxHeightPx: Int,
        ): ImageDecodeResult = result
    }

    private companion object {
        const val MAX_W = 64
        const val MAX_H = 64
    }
}
