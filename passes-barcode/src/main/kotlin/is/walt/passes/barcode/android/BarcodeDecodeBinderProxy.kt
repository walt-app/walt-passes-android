package `is`.walt.passes.barcode.android

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import `is`.walt.passes.core.BarcodeDecodeResult
import kotlinx.coroutines.runBlocking

/**
 * Hand-rolled binder proxy for [BarcodeDecodeBinder]. Avoiding AIDL is deliberate (see
 * [BarcodeDecodeBinder]): this proxy parses exactly one transaction and rejects everything
 * else. Adding a second is intentionally awkward — a reviewer must touch this file, the
 * binder interface, and the surface test before a new method can ship.
 *
 * The reply carries only the pure [BarcodeDecodeResult] shape: a tag plus, on the decoded
 * arm, the payload string and the format wire code. No `Bitmap`, no source bytes, no image
 * metadata ever enters the reply parcel.
 *
 * Coroutines bridge: the transaction runs on a binder thread; [runBlocking] is acceptable
 * because binder transactions are expected to block their thread. The decode work is
 * dispatched back to suspendable code so a future bounded-decode watchdog (wpass-zrt.3/.5)
 * can apply its timeout.
 */
internal class BarcodeDecodeBinderProxy(
    private val impl: BarcodeDecodeBinder,
) : Binder() {
    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean =
        when (code) {
            CODE_DECODE -> handleDecode(data, reply)
            else -> super.onTransact(code, data, reply, flags)
        }

    private fun handleDecode(data: Parcel, reply: Parcel?): Boolean {
        val pfd = data.readTypedObject(ParcelFileDescriptor.CREATOR) ?: return false
        val result = runBlocking { impl.decode(pfd) }
        if (reply != null) {
            when (result) {
                is BarcodeDecodeResult.DecodedBarcode -> {
                    reply.writeInt(TAG_DECODED)
                    reply.writeString(result.payload)
                    reply.writeInt(ScannableFormatWire.encode(result.format))
                }
                BarcodeDecodeResult.NoBarcodeFound -> reply.writeInt(TAG_NO_BARCODE)
                is BarcodeDecodeResult.DecodeFailed -> {
                    reply.writeInt(TAG_FAILED)
                    reply.writeInt(DecodeFailureReasonWire.encode(result.reason))
                }
            }
        }
        return true
    }

    internal companion object {
        const val CODE_DECODE: Int = IBinder.FIRST_CALL_TRANSACTION

        const val TAG_DECODED: Int = 0
        const val TAG_NO_BARCODE: Int = 1
        const val TAG_FAILED: Int = 2
    }
}
