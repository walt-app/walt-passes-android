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
 * Renderer-failure posture: every renderer-side failure mode the consumer can observe
 * is folded into `Rejected(DocumentRejectedKind.RendererFailed)`. That includes:
 *
 *  - The [RemoteException] thrown by [IBinder.transact] when the renderer process is
 *    gone — which is the *expected* shape of a watchdog timeout. `RenderWatchdog` is
 *    engineered to terminate the isolated renderer when a render exceeds its
 *    `timeoutMs`, and its KDoc explicitly promises "the main process then observes the
 *    dropped binder as a RemoteException and surfaces RendererFailed." This client is
 *    where that promise is honoured.
 *  - An unrecognised reply tag or unrecognised rejection-kind code, which would only
 *    occur from a wire-format regression or a compromised isolated process writing
 *    garbage to the reply parcel. Both are folded into `RendererFailed` as a
 *    defense-in-depth against a hostile renderer destabilising the wallet via a
 *    malformed reply; the same regressions are caught structurally at unit-test time
 *    by [PdfRendererBinderRoundTripTest] and [RejectedKindWireSurfaceTest].
 *
 * The consumer's vocabulary is therefore the [DocumentRejectedKind] arm set, not the
 * binder failure-mode set.
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
                try {
                    binder.transact(CODE_PROBE, data, reply, 0)
                } catch (_: RemoteException) {
                    return@withContext ProbeResult.Rejected(DocumentRejectedKind.RendererFailed)
                }
                when (reply.readInt()) {
                    TAG_OK -> ProbeResult.Ok(reply.readInt())
                    TAG_REJECTED -> ProbeResult.Rejected(decodeRejectedKindOrFallback(reply))
                    else -> ProbeResult.Rejected(DocumentRejectedKind.RendererFailed)
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
                try {
                    binder.transact(CODE_RENDER, data, reply, 0)
                } catch (_: RemoteException) {
                    return@withContext RenderResult.Rejected(DocumentRejectedKind.RendererFailed)
                }
                when (reply.readInt()) {
                    TAG_OK -> {
                        val sm = reply.readTypedObject(SharedMemory.CREATOR)
                            ?: return@withContext RenderResult.Rejected(DocumentRejectedKind.RendererFailed)
                        val w = reply.readInt()
                        val h = reply.readInt()
                        RenderResult.Ok(sm, w, h)
                    }
                    TAG_REJECTED -> RenderResult.Rejected(decodeRejectedKindOrFallback(reply))
                    else -> RenderResult.Rejected(DocumentRejectedKind.RendererFailed)
                }
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

    private fun decodeRejectedKindOrFallback(reply: Parcel): DocumentRejectedKind =
        runCatching { RejectedKindWire.decode(reply.readInt()) }
            .getOrDefault(DocumentRejectedKind.RendererFailed)
}
