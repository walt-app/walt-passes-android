package `is`.walt.passes.pdf.android

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SharedMemory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.pdf.DocumentRejectedKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Round-trip coverage for the binder wire format: a real [PdfRendererBinderProxy]
 * wrapping a fake [PdfRendererBinder] is bound to a [PdfRendererClient], and every
 * arm of every reply shape is exercised through `Binder.transact -> onTransact`.
 *
 * Why JVM (Robolectric) rather than instrumented? The wire-format coupling between
 * the proxy and the client is the load-bearing piece for `./gradlew check`. Pinning
 * field-order on both sides at unit-test time means a contributor reordering writes
 * in [PdfRendererBinderProxy] cannot ship without flipping a green local build red.
 * The on-device tests in [PdfRendererServiceInstrumentedTest] cover the parts this
 * test cannot — the actual PDFium decoder, the actual isolated-process binder, the
 * actual cross-process SharedMemory handoff.
 *
 * Same-process [Binder.transact] is documented to dispatch to [onTransact]
 * synchronously and reset parcel positions; the parcels themselves are written and
 * read with the production paths, so wire-shape regressions surface here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PdfRendererBinderRoundTripTest {
    private lateinit var pipeRead: ParcelFileDescriptor
    private lateinit var pipeWrite: ParcelFileDescriptor

    @Before
    fun openPipe() {
        // Use a real pipe rather than ParcelFileDescriptor.adoptFd: a pipe gives us a
        // closeable, parcellable PFD without touching the JVM cwd or filesystem.
        val pipe = ParcelFileDescriptor.createPipe()
        pipeRead = pipe[0]
        pipeWrite = pipe[1]
    }

    @After
    fun closePipe() {
        runCatching { pipeRead.close() }
        runCatching { pipeWrite.close() }
    }

    @Test
    fun probeOkRoundTripCarriesPageCount() = runTest {
        val client = clientFor(StaticImpl(probeResult = ProbeResult.Ok(pageCount = 7)))
        val result = client.probe(pipeRead)
        assertThat(result).isEqualTo(ProbeResult.Ok(7))
    }

    @Test
    fun probeRejectedRoundTripCarriesEachKind() = runTest {
        for (kind in DocumentRejectedKind.entries) {
            val client = clientFor(StaticImpl(probeResult = ProbeResult.Rejected(kind)))
            val result = client.probe(pipeRead)
            assertThat(result).isEqualTo(ProbeResult.Rejected(kind))
        }
    }

    @Test
    fun renderOkRoundTripCarriesSharedMemoryAndDimensions() = runTest {
        val sm = SharedMemory.create("walt-test-render", PIXEL_BYTES)
        val client = clientFor(
            StaticImpl(renderResult = RenderResult.Ok(sm, WIDTH_PX, HEIGHT_PX)),
        )
        val result = client.render(pipeRead, page = 0, widthPx = WIDTH_PX, heightPx = HEIGHT_PX)
        assertThat(result).isInstanceOf(RenderResult.Ok::class.java)
        val ok = result as RenderResult.Ok
        // SharedMemory parcelling produces a fresh handle on the receiver side; size is
        // the stable property we can compare across the marshall boundary. Width/height
        // are written *after* SharedMemory in the wire format, so checking them
        // double-checks the field order: any swap in PdfRendererBinderProxy.handleRender
        // would surface as the wrong int landing in widthPx vs heightPx (or worse,
        // landing in the SharedMemory parcel reader).
        assertThat(ok.sharedMemory.size).isEqualTo(PIXEL_BYTES)
        assertThat(ok.widthPx).isEqualTo(WIDTH_PX)
        assertThat(ok.heightPx).isEqualTo(HEIGHT_PX)
        // Intentionally not calling close() on the SharedMemory handles: Robolectric's
        // FileDescriptor interceptor cannot manipulate raw fd internals under JDK 17
        // (the reflective access path is blocked). The test process exits and reclaims
        // the descriptor regardless; the CloseGuard log line that surfaces is a
        // Robolectric-side artefact, not a leak in production code.
    }

    @Test
    fun renderRejectedRoundTripCarriesEachKind() = runTest {
        for (kind in DocumentRejectedKind.entries) {
            val client = clientFor(StaticImpl(renderResult = RenderResult.Rejected(kind)))
            val result = client.render(pipeRead, page = 0, widthPx = WIDTH_PX, heightPx = HEIGHT_PX)
            assertThat(result).isEqualTo(RenderResult.Rejected(kind))
        }
    }

    @Test
    fun probeFoldsRemoteExceptionIntoRendererFailed() = runTest {
        // RenderWatchdog.guard kills the renderer process on timeout; the main process
        // observes the dropped binder as a RemoteException from `transact`. The
        // watchdog's KDoc explicitly promises consumers see RendererFailed; this test
        // is where that promise is honoured for the probe path.
        val client = PdfRendererClient(DeadBinder())
        val result = client.probe(pipeRead)
        assertThat(result).isEqualTo(ProbeResult.Rejected(DocumentRejectedKind.RendererFailed))
    }

    @Test
    fun renderFoldsRemoteExceptionIntoRendererFailed() = runTest {
        // Same contract as probe; the render path is the more common watchdog target
        // (probe is bounded by page-count, render is bounded by render time).
        val client = PdfRendererClient(DeadBinder())
        val result = client.render(pipeRead, page = 0, widthPx = WIDTH_PX, heightPx = HEIGHT_PX)
        assertThat(result).isEqualTo(RenderResult.Rejected(DocumentRejectedKind.RendererFailed))
    }

    @Test
    fun transactionCodesArePinnedToTheirDocumentedValues() {
        // Pin the absolute codes, not just their distinctness: a contributor flipping
        // the +1 direction (or rebasing CODE_PROBE off a different IBinder constant)
        // would silently swap probe and render on the wire.
        assertThat(PdfRendererBinderProxy.CODE_PROBE)
            .isEqualTo(android.os.IBinder.FIRST_CALL_TRANSACTION)
        assertThat(PdfRendererBinderProxy.CODE_RENDER)
            .isEqualTo(android.os.IBinder.FIRST_CALL_TRANSACTION + 1)
    }

    private fun clientFor(impl: PdfRendererBinder): PdfRendererClient =
        PdfRendererClient(PdfRendererBinderProxy(impl))

    /**
     * Stand-in for an [IBinder] whose remote process is gone: every transact throws
     * [RemoteException]. Same shape the consumer would observe after `RenderWatchdog`
     * kills the isolated renderer.
     */
    private class DeadBinder : Binder() {
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean = throw RemoteException("simulated watchdog kill")
    }

    private class StaticImpl(
        private val probeResult: ProbeResult = ProbeResult.Rejected(DocumentRejectedKind.RendererFailed),
        private val renderResult: RenderResult = RenderResult.Rejected(DocumentRejectedKind.RendererFailed),
    ) : PdfRendererBinder {
        override suspend fun probe(pdf: ParcelFileDescriptor): ProbeResult = probeResult

        override suspend fun render(
            pdf: ParcelFileDescriptor,
            page: Int,
            widthPx: Int,
            heightPx: Int,
        ): RenderResult = renderResult
    }

    private companion object {
        const val WIDTH_PX = 32
        const val HEIGHT_PX = 16
        const val BYTES_PER_PIXEL = 4
        const val PIXEL_BYTES = WIDTH_PX * HEIGHT_PX * BYTES_PER_PIXEL
    }
}
