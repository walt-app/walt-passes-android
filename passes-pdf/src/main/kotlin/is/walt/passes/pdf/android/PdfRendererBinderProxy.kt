package `is`.walt.passes.pdf.android

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking

/**
 * Hand-rolled binder proxy for [PdfRendererBinder]. Avoiding AIDL is deliberate: AIDL
 * generates a Stub class that exposes its own reflectable surface and a parser of
 * arbitrary remote-supplied data. This proxy parses exactly two transactions and
 * rejects everything else. Adding a third is intentionally awkward — a reviewer must
 * touch this file *and* the binder interface *and* the surface test before the new
 * method can ship.
 *
 * Coroutines bridge: each transaction runs on a binder thread; [runBlocking] is
 * acceptable here because the binder thread pool is exactly the model where binder
 * transactions are expected to block. The work is then dispatched back to suspendable
 * code so the watchdog's `withTimeout` mechanism applies.
 */
internal class PdfRendererBinderProxy(
    private val impl: PdfRendererBinder,
) : Binder() {
    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean =
        when (code) {
            CODE_PROBE -> handleProbe(data, reply)
            CODE_RENDER -> handleRender(data, reply)
            else -> super.onTransact(code, data, reply, flags)
        }

    private fun handleProbe(data: Parcel, reply: Parcel?): Boolean {
        val pfd = data.readTypedObject(ParcelFileDescriptor.CREATOR) ?: return false
        val result = runBlocking { impl.probe(pfd) }
        if (reply != null) {
            when (result) {
                is ProbeResult.Ok -> {
                    reply.writeInt(TAG_OK)
                    reply.writeInt(result.pageCount)
                }
                is ProbeResult.Rejected -> {
                    reply.writeInt(TAG_REJECTED)
                    reply.writeInt(RejectedKindWire.encode(result.kind))
                }
            }
        }
        return true
    }

    @Suppress("ReturnCount")
    private fun handleRender(data: Parcel, reply: Parcel?): Boolean {
        val pfd = data.readTypedObject(ParcelFileDescriptor.CREATOR) ?: return false
        val page = data.readInt()
        val widthPx = data.readInt()
        val heightPx = data.readInt()
        val sourceRect = readSourceRect(data) ?: return false
        val result = runBlocking { impl.render(pfd, page, widthPx, heightPx, sourceRect) }
        if (reply != null) {
            when (result) {
                is RenderResult.Ok -> {
                    reply.writeInt(TAG_OK)
                    reply.writeTypedObject(result.sharedMemory, 0)
                    reply.writeInt(result.widthPx)
                    reply.writeInt(result.heightPx)
                }
                is RenderResult.Rejected -> {
                    reply.writeInt(TAG_REJECTED)
                    reply.writeInt(RejectedKindWire.encode(result.kind))
                }
            }
        }
        return true
    }

    // Null on an unknown tag short-circuits handleRender to onTransact `false`, which
    // the client folds into RendererFailed.
    private fun readSourceRect(data: Parcel): RenderSourceRect? =
        when (data.readInt()) {
            TAG_SOURCE_RECT_FULL_PAGE -> RenderSourceRect.FullPage
            TAG_SOURCE_RECT_SUB_RECT -> RenderSourceRect.SubRect(
                left = data.readFloat(),
                top = data.readFloat(),
                right = data.readFloat(),
                bottom = data.readFloat(),
            )
            else -> null
        }

    internal companion object {
        const val CODE_PROBE: Int = IBinder.FIRST_CALL_TRANSACTION
        const val CODE_RENDER: Int = IBinder.FIRST_CALL_TRANSACTION + 1

        const val TAG_OK: Int = 0
        const val TAG_REJECTED: Int = 1

        // Distinct namespace from TAG_OK / TAG_REJECTED so a positional collision in
        // the wire format would not silently swap meaning.
        const val TAG_SOURCE_RECT_FULL_PAGE: Int = 0
        const val TAG_SOURCE_RECT_SUB_RECT: Int = 1
    }
}
