package `is`.walt.passes.image.android

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SharedMemory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Round-trip coverage for the decode binder wire format: a real [ImageDecodeBinderProxy]
 * wrapping a fake [ImageDecodeBinder] is bound to an [ImageDecodeClient], and every arm of the
 * reply shape is exercised through `Binder.transact -> onTransact`. Mirrors `passes-pdf`'s
 * `PdfRendererBinderRoundTripTest` and `passes-barcode`'s `BarcodeDecodeBinderRoundTripTest`.
 *
 * Why JVM (Robolectric) rather than instrumented? The wire-format coupling between the proxy
 * and the client is the load-bearing piece for `./gradlew check`. Pinning the field order on
 * both sides at unit-test time means a contributor reordering writes in the proxy cannot ship
 * without flipping a green local build red. The on-device test in
 * [ImageDecodeServiceInstrumentedTest] covers the parts this cannot — the real isolated
 * process, the real platform codec, and the permission-isolation assertion.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImageDecodeBinderRoundTripTest {
    private lateinit var pipeRead: ParcelFileDescriptor
    private lateinit var pipeWrite: ParcelFileDescriptor

    @Before
    fun openPipe() {
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
    fun okRoundTripCarriesRasterHandleDimensionsAndAspect() = runTest {
        val sm = SharedMemory.create("walt-test-image", PIXEL_BYTES)
        val client = clientFor(StaticImpl(ImageDecodeResult.Ok(sm, WIDTH_PX, HEIGHT_PX, ASPECT)))

        val result = client.decode(pipeRead, maxWidthPx = WIDTH_PX, maxHeightPx = HEIGHT_PX)

        assertThat(result).isInstanceOf(ImageDecodeResult.Ok::class.java)
        val ok = result as ImageDecodeResult.Ok
        // SharedMemory parcelling produces a fresh handle on the receiver side; size is the
        // stable property comparable across the marshall boundary. Width/height are written
        // *after* SharedMemory in the wire format, so checking them double-checks field order:
        // any swap in handleDecode would land the wrong int in widthPx vs heightPx.
        assertThat(ok.sharedMemory.size).isEqualTo(PIXEL_BYTES)
        assertThat(ok.widthPx).isEqualTo(WIDTH_PX)
        assertThat(ok.heightPx).isEqualTo(HEIGHT_PX)
        assertThat(ok.sourceAspect).isEqualTo(ASPECT)
        // Not closing the SharedMemory handles: Robolectric's FileDescriptor interceptor cannot
        // manipulate raw fd internals under JDK 17; the test process reclaims them on exit.
    }

    @Test
    fun maxDimensionsAreCarriedToTheImplAcrossTheWire() = runTest {
        val sm = SharedMemory.create("walt-test-image-dims", PIXEL_BYTES)
        val impl = StaticImpl(ImageDecodeResult.Ok(sm, WIDTH_PX, HEIGHT_PX, ASPECT))
        val client = clientFor(impl)

        client.decode(pipeRead, maxWidthPx = 123, maxHeightPx = 456)

        assertThat(impl.lastMaxWidthPx).isEqualTo(123)
        assertThat(impl.lastMaxHeightPx).isEqualTo(456)
    }

    @Test
    fun rejectedRoundTripCarriesEachKind() = runTest {
        for (kind in allKinds) {
            val client = clientFor(StaticImpl(ImageDecodeResult.Rejected(kind)))
            assertThat(client.decode(pipeRead, WIDTH_PX, HEIGHT_PX))
                .isEqualTo(ImageDecodeResult.Rejected(kind))
        }
    }

    @Test
    fun decodeFoldsRemoteExceptionIntoDecoderUnavailable() = runTest {
        val client = ImageDecodeClient(DeadBinder())
        assertThat(client.decode(pipeRead, WIDTH_PX, HEIGHT_PX))
            .isEqualTo(ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecoderUnavailable))
    }

    @Test
    fun decodeFoldsTransactFalseIntoDecoderUnavailable() = runTest {
        // onTransact returning false leaves the reply empty; decoding it as TAG_OK (empty
        // parcel reads 0) would surface a phantom Ok with a null SharedMemory, so the client
        // must translate false into DecoderUnavailable.
        val client = ImageDecodeClient(FalseBinder())
        assertThat(client.decode(pipeRead, WIDTH_PX, HEIGHT_PX))
            .isEqualTo(ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecoderUnavailable))
    }

    @Test
    fun decodeFoldsMalformedReplyIntoDecoderUnavailable() = runTest {
        // A compromised sandbox could return an unrecognised tag; the client treats the reply
        // as untrusted and folds any parse failure to DecoderUnavailable rather than throwing.
        val client = ImageDecodeClient(GarbageReplyBinder())
        assertThat(client.decode(pipeRead, WIDTH_PX, HEIGHT_PX))
            .isEqualTo(ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecoderUnavailable))
    }

    @Test
    fun transactionCodeIsPinnedToItsDocumentedValue() {
        assertThat(ImageDecodeBinderProxy.CODE_DECODE).isEqualTo(IBinder.FIRST_CALL_TRANSACTION)
    }

    private fun clientFor(impl: ImageDecodeBinder): ImageDecodeClient =
        ImageDecodeClient(ImageDecodeBinderProxy(impl))

    private val allKinds: List<ImageDecodeRejectedKind> =
        listOf(
            ImageDecodeRejectedKind.NotAnImage,
            ImageDecodeRejectedKind.OversizedAtImport,
            ImageDecodeRejectedKind.DimensionsTooLarge,
            ImageDecodeRejectedKind.DecodeFailed,
            ImageDecodeRejectedKind.DecoderUnavailable,
        )

    /** Every transact throws RemoteException — the shape after the decode process is gone. */
    private class DeadBinder : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
            throw RemoteException("simulated decode-process death")
    }

    /** onTransact returns false without writing a reply — the proxy's unreadable-request path. */
    private class FalseBinder : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
    }

    /** Returns a well-formed transaction whose reply carries an unrecognised tag. */
    private class GarbageReplyBinder : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            reply?.writeInt(Int.MAX_VALUE)
            return true
        }
    }

    private class StaticImpl(
        private val result: ImageDecodeResult,
    ) : ImageDecodeBinder {
        var lastMaxWidthPx: Int = -1
            private set
        var lastMaxHeightPx: Int = -1
            private set

        // Intentionally does NOT close `image`: under Robolectric's same-process transact the
        // received PFD aliases the test's pipe rather than a cross-process dup. The production
        // service closes its received fd; that path is asserted on-device, not here.
        override suspend fun decode(
            image: ParcelFileDescriptor,
            maxWidthPx: Int,
            maxHeightPx: Int,
        ): ImageDecodeResult {
            lastMaxWidthPx = maxWidthPx
            lastMaxHeightPx = maxHeightPx
            return result
        }
    }

    private companion object {
        const val WIDTH_PX = 32
        const val HEIGHT_PX = 16
        const val ASPECT = 2.0f
        const val BYTES_PER_PIXEL = 4
        const val PIXEL_BYTES = WIDTH_PX * HEIGHT_PX * BYTES_PER_PIXEL
    }
}
