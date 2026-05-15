package `is`.walt.passes.pdf.android

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SharedMemory
import `is`.walt.passes.pdf.DocumentRejectedKind
import `is`.walt.passes.pdf.android.PdfRendererBinderProxy.Companion.CODE_PROBE
import `is`.walt.passes.pdf.android.PdfRendererBinderProxy.Companion.CODE_RENDER
import `is`.walt.passes.pdf.android.PdfRendererBinderProxy.Companion.TAG_OK
import `is`.walt.passes.pdf.android.PdfRendererBinderProxy.Companion.TAG_REJECTED
import `is`.walt.passes.pdf.android.PdfRendererBinderProxy.Companion.TAG_SOURCE_RECT_FULL_PAGE
import `is`.walt.passes.pdf.android.PdfRendererBinderProxy.Companion.TAG_SOURCE_RECT_SUB_RECT
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
 * Failure-mode posture — runtime vs. wire invariants:
 *
 *  - The [RemoteException] thrown by [IBinder.transact] when the renderer process is
 *    gone is the *designed* runtime failure mode. `RenderWatchdog` is engineered to
 *    terminate the isolated renderer when a render exceeds its `timeoutMs`, and its
 *    KDoc explicitly promises "the main process then observes the dropped binder as a
 *    RemoteException and surfaces RendererFailed." This client is where that promise
 *    is honoured: the exception is caught and folded into
 *    [DocumentRejectedKind.RendererFailed].
 *  - A `false` return from [IBinder.transact] is treated the same way, defensively.
 *    The shape would arise from a proxy `onTransact` that bailed before writing the
 *    reply parcel; in practice the only path that returns false is the proxy failing
 *    to read a [ParcelFileDescriptor] out of a parcel it just received, which is a
 *    wire-invariant violation (same module, same build) rather than a real runtime
 *    condition. Folding it in costs one line and avoids the alternative — decoding an
 *    empty reply parcel where `readInt()` returns `0`, which equals `TAG_OK`, would
 *    surface a phantom `Ok(0)`.
 *  - Unrecognised reply tags, missing SharedMemory on a `TAG_OK` render reply, and
 *    unrecognised rejection-kind codes are wire-invariant assertions and fail-fast
 *    via [error]. Same-module proxy and client are required to agree on the wire
 *    format by construction; a disagreement is a programmer error to be caught at the
 *    nearest test run, not silently absorbed by the consumer. The structural gate is
 *    [PdfRendererBinderRoundTripTest] (wire shape) and [RejectedKindWireSurfaceTest]
 *    (rejection-kind table).
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
                val accepted =
                    try {
                        binder.transact(CODE_PROBE, data, reply, 0)
                    } catch (_: RemoteException) {
                        return@withContext ProbeResult.Rejected(DocumentRejectedKind.RendererFailed)
                    }
                if (!accepted) {
                    return@withContext ProbeResult.Rejected(DocumentRejectedKind.RendererFailed)
                }
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
        sourceRect: RenderSourceRect,
    ): RenderResult =
        withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeTypedObject(pdf, 0)
                data.writeInt(page)
                data.writeInt(widthPx)
                data.writeInt(heightPx)
                writeSourceRect(data, sourceRect)
                val accepted =
                    try {
                        binder.transact(CODE_RENDER, data, reply, 0)
                    } catch (_: RemoteException) {
                        return@withContext RenderResult.Rejected(DocumentRejectedKind.RendererFailed)
                    }
                if (!accepted) {
                    return@withContext RenderResult.Rejected(DocumentRejectedKind.RendererFailed)
                }
                when (val tag = reply.readInt()) {
                    TAG_OK -> {
                        val sm = reply.readTypedObject(SharedMemory.CREATOR)
                            ?: error("Render reply missing SharedMemory")
                        val w = reply.readInt()
                        val h = reply.readInt()
                        val pageAspect = reply.readFloat()
                        RenderResult.Ok(sm, w, h, pageAspect)
                    }
                    TAG_REJECTED -> RenderResult.Rejected(RejectedKindWire.decode(reply.readInt()))
                    else -> error("Unknown render reply tag: $tag")
                }
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

    private fun writeSourceRect(data: Parcel, sourceRect: RenderSourceRect) {
        when (sourceRect) {
            is RenderSourceRect.FullPage -> data.writeInt(TAG_SOURCE_RECT_FULL_PAGE)
            is RenderSourceRect.SubRect -> {
                data.writeInt(TAG_SOURCE_RECT_SUB_RECT)
                data.writeFloat(sourceRect.left)
                data.writeFloat(sourceRect.top)
                data.writeFloat(sourceRect.right)
                data.writeFloat(sourceRect.bottom)
            }
        }
    }
}
