package `is`.walt.passes.barcode.android

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.DecodeFailureReason
import `is`.walt.passes.core.ScannableFormat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Round-trip coverage for the decode binder wire format: a real [BarcodeDecodeBinderProxy]
 * wrapping a fake [BarcodeDecodeBinder] is bound to a [BarcodeDecodeClient], and every arm
 * of the reply shape is exercised through `Binder.transact -> onTransact`. Mirrors
 * `passes-pdf`'s `PdfRendererBinderRoundTripTest`.
 *
 * Why JVM (Robolectric) rather than instrumented? The wire-format coupling between the
 * proxy and the client is the load-bearing piece for `./gradlew check`. Pinning the field
 * order on both sides at unit-test time means a contributor reordering writes in the proxy
 * cannot ship without flipping a green local build red. The on-device test in
 * [BarcodeDecodeServiceInstrumentedTest] covers the parts this cannot — the real isolated
 * process and the permission-isolation assertion.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BarcodeDecodeBinderRoundTripTest {
    private lateinit var pipeRead: ParcelFileDescriptor
    private lateinit var pipeWrite: ParcelFileDescriptor

    @Before
    fun openPipe() {
        // A real pipe gives a closeable, parcellable PFD without touching the filesystem.
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
    fun decodedBarcodeRoundTripCarriesPayloadAndEachFormat() = runTest {
        for (format in ScannableFormat.entries) {
            val client =
                clientFor(StaticImpl(BarcodeDecodeResult.DecodedBarcode(payload = "PAYLOAD-$format", format = format)))
            val result = client.decode(pipeRead)
            assertThat(result).isEqualTo(BarcodeDecodeResult.DecodedBarcode("PAYLOAD-$format", format))
        }
    }

    @Test
    fun noBarcodeFoundRoundTrips() = runTest {
        val client = clientFor(StaticImpl(BarcodeDecodeResult.NoBarcodeFound))
        assertThat(client.decode(pipeRead)).isEqualTo(BarcodeDecodeResult.NoBarcodeFound)
    }

    @Test
    fun decodeFailedRoundTripsCarriesEachReason() = runTest {
        for (reason in DecodeFailureReason.entries) {
            val client = clientFor(StaticImpl(BarcodeDecodeResult.DecodeFailed(reason)))
            assertThat(client.decode(pipeRead)).isEqualTo(BarcodeDecodeResult.DecodeFailed(reason))
        }
    }

    @Test
    fun emptyPayloadRoundTripsFaithfully() = runTest {
        // A symbol carrying an empty string is distinct from NoBarcodeFound; the wire must
        // preserve the empty payload rather than collapse it.
        val client = clientFor(StaticImpl(BarcodeDecodeResult.DecodedBarcode("", ScannableFormat.Qr)))
        assertThat(client.decode(pipeRead)).isEqualTo(BarcodeDecodeResult.DecodedBarcode("", ScannableFormat.Qr))
    }

    @Test
    fun decodeFoldsRemoteExceptionIntoDecoderUnavailable() = runTest {
        // A decode process that goes away (e.g. killed by a future bounded-decode watchdog)
        // surfaces as RemoteException from transact; the client folds it to DecoderUnavailable.
        val client = BarcodeDecodeClient(DeadBinder())
        assertThat(client.decode(pipeRead))
            .isEqualTo(BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.DecoderUnavailable))
    }

    @Test
    fun decodeFoldsTransactFalseIntoDecoderUnavailable() = runTest {
        // onTransact returning false (proxy could not read the request PFD) leaves the reply
        // parcel empty; decoding it as TAG_DECODED (empty parcel reads 0) would surface a
        // phantom decoded result, so the client must translate false into DecoderUnavailable.
        val client = BarcodeDecodeClient(FalseBinder())
        assertThat(client.decode(pipeRead))
            .isEqualTo(BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.DecoderUnavailable))
    }

    @Test
    fun transactionCodeIsPinnedToItsDocumentedValue() {
        assertThat(BarcodeDecodeBinderProxy.CODE_DECODE).isEqualTo(IBinder.FIRST_CALL_TRANSACTION)
    }

    private fun clientFor(impl: BarcodeDecodeBinder): BarcodeDecodeClient =
        BarcodeDecodeClient(BarcodeDecodeBinderProxy(impl))

    /** Every transact throws RemoteException — the shape after the decode process is gone. */
    private class DeadBinder : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
            throw RemoteException("simulated decode-process death")
    }

    /** onTransact returns false without writing a reply — the proxy's unreadable-request path. */
    private class FalseBinder : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
    }

    private class StaticImpl(
        private val result: BarcodeDecodeResult,
    ) : BarcodeDecodeBinder {
        // Intentionally does NOT close `image`: under Robolectric's same-process transact the
        // received PFD aliases the test's pipe rather than a cross-process dup, and the
        // multi-format loops reuse that pipe across iterations. The production service closes
        // its received fd; that path is asserted on-device, not here.
        override suspend fun decode(image: ParcelFileDescriptor): BarcodeDecodeResult = result
    }
}
