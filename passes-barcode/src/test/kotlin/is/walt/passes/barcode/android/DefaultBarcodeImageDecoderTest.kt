package `is`.walt.passes.barcode.android

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.DecodeFailureReason
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.isolation.ConnectResult
import `is`.walt.passes.isolation.IsolatedWorkerSession
import `is`.walt.passes.isolation.IsolatedWorkerSessionFactory
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Behavioural coverage for [DefaultBarcodeImageDecoder]. Each test pins one rule the decoder
 * promises — mirroring `passes-pdf`'s `PdfImporterTest`, scoped to the smaller decode
 * orchestration:
 *
 *  - An unreadable / disallowed-scheme source short-circuits to `SourceUnreadable` *before*
 *    any bind is attempted.
 *  - A failed bind folds to `DecoderUnavailable`.
 *  - The binder's result rounds back through `decode` unchanged (the wire arms are pinned
 *    separately by [BarcodeDecodeBinderRoundTripTest]).
 *  - The session is unbound and the source PFD is closed on *every* outcome (the bind-first
 *    / reverse-order teardown discipline).
 *
 * The bind and the source open are injected as `Deps` seams, so the orchestration runs
 * without a live isolated process or a real `ContentResolver`. The public surface
 * (`BarcodeImageDecoder.create`) stays untouched.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DefaultBarcodeImageDecoderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun unreadableSourceShortCircuitsBeforeBinding() = runTest {
        val factory = RecordingSessionFactory()
        val decoder = decoder(sessionFactory = factory, openPfd = { null })

        val result = decoder.decode(fileDescriptorSource())

        assertThat(result).isEqualTo(BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.SourceUnreadable))
        assertThat(factory.connectCalls).isEqualTo(0)
    }

    @Test
    fun bindFailureFoldsToDecoderUnavailableAndClosesPfd() = runTest {
        val pfd = TrackingPfd(pipeReadEnd())
        val factory = RecordingSessionFactory(connectResult = ConnectResult.BindFailed)
        val decoder = decoder(sessionFactory = factory, openPfd = { pfd })

        val result = decoder.decode(fileDescriptorSource())

        assertThat(result).isEqualTo(BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.DecoderUnavailable))
        assertThat(factory.connectCalls).isEqualTo(1)
        assertThat(pfd.closed).isTrue()
    }

    @Test
    fun noBarcodeFoundRoundsThroughAndTearsDown() = runTest {
        val pfd = TrackingPfd(pipeReadEnd())
        val factory = RecordingSessionFactory(binder = StaticDecodeBinder(BarcodeDecodeResult.NoBarcodeFound))
        val decoder = decoder(sessionFactory = factory, openPfd = { pfd })

        val result = decoder.decode(fileDescriptorSource())

        assertThat(result).isEqualTo(BarcodeDecodeResult.NoBarcodeFound)
        assertThat(factory.lastSession?.closed).isTrue()
        assertThat(pfd.closed).isTrue()
    }

    @Test
    fun decodedBarcodeRoundsThroughAndTearsDown() = runTest {
        val decoded = BarcodeDecodeResult.DecodedBarcode(payload = "PASS-123", format = ScannableFormat.Code128)
        val pfd = TrackingPfd(pipeReadEnd())
        val factory = RecordingSessionFactory(binder = StaticDecodeBinder(decoded))
        val decoder = decoder(sessionFactory = factory, openPfd = { pfd })

        val result = decoder.decode(fileDescriptorSource())

        assertThat(result).isEqualTo(decoded)
        assertThat(factory.lastSession?.closed).isTrue()
        assertThat(pfd.closed).isTrue()
    }

    @Test
    fun sessionIsClosedEvenWhenBinderReportsFailure() = runTest {
        val pfd = TrackingPfd(pipeReadEnd())
        val factory =
            RecordingSessionFactory(
                binder = StaticDecodeBinder(BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.ImageTooLarge)),
            )
        val decoder = decoder(sessionFactory = factory, openPfd = { pfd })

        val result = decoder.decode(fileDescriptorSource())

        assertThat(result).isEqualTo(BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.ImageTooLarge))
        assertThat(factory.lastSession?.closed).isTrue()
        assertThat(pfd.closed).isTrue()
    }

    @Test
    fun nonContentSchemeUriIsRejectedByDefaultOpener() {
        // file:// is the canonical escape-hatch shape the scheme allowlist closes:
        // openFileDescriptor would otherwise resolve an arbitrary filesystem path.
        val fileUri = Uri.parse("file:///data/data/example/cache/x.png")
        val source = BarcodeImageSource.ContentUri(fileUri, context.contentResolver)
        assertThat(DefaultBarcodeImageDecoder.defaultOpenPfd(source)).isNull()
    }

    // --------------------------------------------------------------------- helpers

    private fun decoder(
        sessionFactory: IsolatedWorkerSessionFactory<BarcodeDecodeBinder>,
        openPfd: (BarcodeImageSource) -> ParcelFileDescriptor?,
    ): DefaultBarcodeImageDecoder =
        DefaultBarcodeImageDecoder(
            appContext = context,
            deps = DefaultBarcodeImageDecoder.Deps(
                sessionFactoryFor = { sessionFactory },
                openPfd = openPfd,
            ),
        )

    private fun fileDescriptorSource(): BarcodeImageSource =
        BarcodeImageSource.FileDescriptor(pipeReadEnd())

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
        private val binder: BarcodeDecodeBinder = StaticDecodeBinder(BarcodeDecodeResult.NoBarcodeFound),
        private val connectResult: ConnectResult<BarcodeDecodeBinder>? = null,
    ) : IsolatedWorkerSessionFactory<BarcodeDecodeBinder> {
        var connectCalls: Int = 0
        var lastSession: RecordingSession? = null

        override suspend fun connect(): ConnectResult<BarcodeDecodeBinder> {
            connectCalls++
            connectResult?.let { return it }
            val s = RecordingSession(binder)
            lastSession = s
            return ConnectResult.Connected(s)
        }
    }

    private class RecordingSession(
        override val client: BarcodeDecodeBinder,
    ) : IsolatedWorkerSession<BarcodeDecodeBinder> {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
        }
    }

    private class StaticDecodeBinder(
        private val result: BarcodeDecodeResult,
    ) : BarcodeDecodeBinder {
        override suspend fun decode(image: ParcelFileDescriptor): BarcodeDecodeResult = result
    }
}
