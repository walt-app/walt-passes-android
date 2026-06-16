package `is`.walt.passes.image.android

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import kotlinx.coroutines.runBlocking

/**
 * Hand-rolled binder proxy for [ImageDecodeBinder]. Avoiding AIDL is deliberate (see
 * [ImageDecodeBinder]): this proxy parses exactly one transaction and rejects everything else.
 * Adding a second is intentionally awkward — a reviewer must touch this file, the binder
 * interface, and the surface test before a new method can ship.
 *
 * The reply carries only the [ImageDecodeResult] shape: a tag plus, on the Ok arm, the
 * [SharedMemory] handle and the raster dimensions / source aspect. No source bytes, no
 * caller-supplied `Bitmap`, no image metadata ever enters the reply parcel — only the bounded
 * Walt-produced raster the sandbox just created. Mirrors `PdfRendererBinderProxy`.
 *
 * Coroutines bridge: the transaction runs on a binder thread; [runBlocking] is acceptable
 * because binder transactions are expected to block their thread. The decode work is
 * dispatched back to suspendable code so the [DecodeWatchdog] can apply its timeout.
 */
internal class ImageDecodeBinderProxy(
    private val impl: ImageDecodeBinder,
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
        val maxWidthPx = data.readInt()
        val maxHeightPx = data.readInt()
        val result = runBlocking { impl.decode(pfd, maxWidthPx, maxHeightPx) }
        if (reply != null) {
            when (result) {
                is ImageDecodeResult.Ok -> {
                    reply.writeInt(TAG_OK)
                    reply.writeTypedObject(result.sharedMemory, 0)
                    reply.writeInt(result.widthPx)
                    reply.writeInt(result.heightPx)
                    reply.writeFloat(result.sourceAspect)
                }
                is ImageDecodeResult.Rejected -> {
                    reply.writeInt(TAG_REJECTED)
                    reply.writeInt(ImageDecodeRejectedKindWire.encode(result.kind))
                }
            }
        }
        return true
    }

    internal companion object {
        const val CODE_DECODE: Int = IBinder.FIRST_CALL_TRANSACTION

        const val TAG_OK: Int = 0
        const val TAG_REJECTED: Int = 1
    }
}
