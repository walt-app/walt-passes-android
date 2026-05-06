package `is`.walt.passes.pdf.android

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import `is`.walt.passes.pdf.android.PdfRendererBinderProxy.Companion.CODE_PROBE
import `is`.walt.passes.pdf.android.PdfRendererBinderProxy.Companion.CODE_RENDER
import `is`.walt.passes.pdf.android.PdfRendererBinderProxy.Companion.TAG_OK
import `is`.walt.passes.pdf.android.PdfRendererBinderProxy.Companion.TAG_REJECTED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 *
 * Cancellation: [IBinder.transact] is a synchronous, uninterruptible call. The body is
 * dispatched on [Dispatchers.IO] so a `withTimeout { client.render(...) }` from the
 * caller can cooperatively cancel and free the calling coroutine; the underlying
 * transact still runs to completion on an IO worker, but the caller's coroutine
 * returns. The authoritative timeout for runaway renders lives on the *server* side via
 * `RenderWatchdog`; the client cannot abort an in-flight binder call.
 *
 * Wire-drift posture: an unknown reply tag or rejection-kind code throws
 * [IllegalStateException]. The choice is to surface wire-shape regressions loudly
 * rather than fold them into `Rejected(RendererFailed)`, on the basis that proxy and
 * client live in the same module and the same build — a mismatch is a structural bug,
 * not a runtime condition the consumer should silently absorb. If a future cross-build
 * scenario emerges (e.g. a downstream consumer pinning an older `passes-pdf` jar), this
 * posture should be revisited.
 */
public class PdfRendererClient(
    private val binder: IBinder,
) : PdfRendererBinder {
    override suspend fun probe(pdf: ParcelFileDescriptor): ProbeResult =
        withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeTypedObject(pdf, 0)
                binder.transact(CODE_PROBE, data, reply, 0)
                when (val tag = reply.readInt()) {
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
    ): RenderResult =
        withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeTypedObject(pdf, 0)
                data.writeInt(page)
                data.writeInt(widthPx)
                data.writeInt(heightPx)
                binder.transact(CODE_RENDER, data, reply, 0)
                when (val tag = reply.readInt()) {
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
