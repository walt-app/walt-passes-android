package `is`.walt.passes.pdf.android

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import `is`.walt.passes.pdf.android.PdfRendererBinderProxy.Companion.CODE_PROBE
import `is`.walt.passes.pdf.android.PdfRendererBinderProxy.Companion.CODE_RENDER
import `is`.walt.passes.pdf.android.PdfRendererBinderProxy.Companion.TAG_OK
import `is`.walt.passes.pdf.android.PdfRendererBinderProxy.Companion.TAG_REJECTED

/**
 * Client-side counterpart to [PdfRendererBinderProxy]. Wraps a bound [IBinder]
 * (obtained from `Context.bindService` against [PdfRendererService]) and exposes the
 * exact same suspend contract as [PdfRendererBinder]: probe-or-rejection, render-or-
 * rejection, no extraction surface.
 *
 * Implementing [PdfRendererBinder] rather than re-declaring the methods means
 * `PublicApiSurfaceTest` on the interface already covers the client's surface; a future
 * `getText` passthrough cannot land without breaking that test. [PdfRendererClientSurfaceTest]
 * adds a second lock at the class level so a contributor cannot grow the client with a
 * non-overriding helper that bypasses the interface's trust contract.
 *
 * The wire format is owned by [PdfRendererBinderProxy.Companion] (transaction codes,
 * tag bytes) and [RejectedKindWire] (rejection-kind codes); [PdfRendererBinderRoundTripTest]
 * pins the field order on both sides.
 */
public class PdfRendererClient(
    private val binder: IBinder,
) : PdfRendererBinder {
    override suspend fun probe(pdf: ParcelFileDescriptor): ProbeResult {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeTypedObject(pdf, 0)
            binder.transact(CODE_PROBE, data, reply, 0)
            return when (val tag = reply.readInt()) {
                TAG_OK -> ProbeResult.Ok(reply.readInt())
                TAG_REJECTED -> ProbeResult.Rejected(RejectedKindWire.decode(reply.readInt()))
                else -> error("Unknown probe reply tag: $tag")
            }
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    override suspend fun render(
        pdf: ParcelFileDescriptor,
        page: Int,
        widthPx: Int,
        heightPx: Int,
    ): RenderResult {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeTypedObject(pdf, 0)
            data.writeInt(page)
            data.writeInt(widthPx)
            data.writeInt(heightPx)
            binder.transact(CODE_RENDER, data, reply, 0)
            return when (val tag = reply.readInt()) {
                TAG_OK -> {
                    val sm = reply.readTypedObject(SharedMemory.CREATOR)
                        ?: error("Render reply missing SharedMemory")
                    val w = reply.readInt()
                    val h = reply.readInt()
                    RenderResult.Ok(sm, w, h)
                }
                TAG_REJECTED -> RenderResult.Rejected(RejectedKindWire.decode(reply.readInt()))
                else -> error("Unknown render reply tag: $tag")
            }
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
