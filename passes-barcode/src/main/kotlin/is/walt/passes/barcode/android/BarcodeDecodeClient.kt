package `is`.walt.passes.barcode.android

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import `is`.walt.passes.barcode.android.BarcodeDecodeBinderProxy.Companion.CODE_DECODE
import `is`.walt.passes.barcode.android.BarcodeDecodeBinderProxy.Companion.TAG_DECODED
import `is`.walt.passes.barcode.android.BarcodeDecodeBinderProxy.Companion.TAG_FAILED
import `is`.walt.passes.barcode.android.BarcodeDecodeBinderProxy.Companion.TAG_NO_BARCODE
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.DecodeFailureReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Client-side counterpart to [BarcodeDecodeBinderProxy]. Wraps a bound [IBinder] (obtained
 * from `Context.bindService` against [BarcodeDecodeService] through the shared isolation
 * facade) and exposes the same suspend contract as [BarcodeDecodeBinder]: one decode call,
 * a pure result, no extraction surface.
 *
 * Implementing [BarcodeDecodeBinder] rather than re-declaring `decode` means
 * [BarcodeDecodeBinderSurfaceTest] on the interface already covers the client's surface; a
 * future extraction passthrough cannot land without breaking it.
 *
 * Failure-mode posture mirrors `passes-pdf`'s `PdfRendererClient`:
 *
 *  - A [RemoteException] from [IBinder.transact] is the designed runtime failure mode for a
 *    decode process that went away (e.g. killed by a future bounded-decode watchdog). It is
 *    folded into [DecodeFailureReason.DecoderUnavailable] — "the isolated decode process
 *    could not be bound or terminated before returning a result."
 *  - A `false` return from [IBinder.transact] is treated the same way, defensively: the
 *    only path that returns false is the proxy failing to read the PFD out of the request
 *    parcel (a same-build wire-invariant violation). Folding it in avoids decoding an empty
 *    reply parcel where `readInt()` returns 0 (which equals [TAG_DECODED]) and surfacing a
 *    phantom decoded result.
 *  - Unrecognised reply tags, a missing payload on a [TAG_DECODED] reply, and unrecognised
 *    wire codes are wire-invariant assertions and fail-fast via [error]; the structural
 *    gates are [BarcodeDecodeBinderRoundTripTest] and the two wire-surface tests.
 */
public class BarcodeDecodeClient(
    private val binder: IBinder,
) : BarcodeDecodeBinder {
    override suspend fun decode(image: ParcelFileDescriptor): BarcodeDecodeResult =
        withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeTypedObject(image, 0)
                val accepted =
                    try {
                        binder.transact(CODE_DECODE, data, reply, 0)
                    } catch (_: RemoteException) {
                        return@withContext decoderUnavailable()
                    }
                if (!accepted) {
                    return@withContext decoderUnavailable()
                }
                when (val tag = reply.readInt()) {
                    TAG_DECODED ->
                        BarcodeDecodeResult.DecodedBarcode(
                            payload = reply.readString() ?: error("Decode reply missing payload"),
                            format = ScannableFormatWire.decode(reply.readInt()),
                        )
                    TAG_NO_BARCODE -> BarcodeDecodeResult.NoBarcodeFound
                    TAG_FAILED -> BarcodeDecodeResult.DecodeFailed(DecodeFailureReasonWire.decode(reply.readInt()))
                    else -> error("Unknown decode reply tag: $tag")
                }
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

    private fun decoderUnavailable(): BarcodeDecodeResult =
        BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.DecoderUnavailable)
}
