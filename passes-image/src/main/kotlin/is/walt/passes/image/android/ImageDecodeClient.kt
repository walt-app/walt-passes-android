package `is`.walt.passes.image.android

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SharedMemory
import `is`.walt.passes.image.android.ImageDecodeBinderProxy.Companion.CODE_DECODE
import `is`.walt.passes.image.android.ImageDecodeBinderProxy.Companion.TAG_OK
import `is`.walt.passes.image.android.ImageDecodeBinderProxy.Companion.TAG_REJECTED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Client-side counterpart to [ImageDecodeBinderProxy]. Wraps a bound [IBinder] (obtained from
 * `Context.bindService` against [ImageDecodeService] through the shared `passes-isolation`
 * facade) and exposes the same suspend contract as [ImageDecodeBinder]: one decode call, a
 * raster-or-rejection result, no extraction surface.
 *
 * Implementing [ImageDecodeBinder] rather than re-declaring `decode` means
 * [ImageDecodeBinderSurfaceTest] on the interface already covers the client's surface; a
 * future extraction passthrough cannot land without breaking it. `public` so a host (the
 * wpass-i9x consumer) can build the client over its own bound binder, but the display surface
 * is expected to take the [ImageDecodeBinder] interface, never this concrete type — same
 * discipline as `passes-pdf`'s `PdfRendererClient`.
 *
 * Failure-mode posture mirrors `passes-barcode`'s `BarcodeDecodeClient` (the harder line than
 * `passes-pdf`'s fail-fast client, because the reply's sender is the isolated decode process,
 * which this feature's threat model assumes may be compromised):
 *
 *  - A [RemoteException] from [IBinder.transact] (the decode process went away, e.g. killed by
 *    the watchdog) folds to [ImageDecodeRejectedKind.DecoderUnavailable].
 *  - A `false` return from [IBinder.transact] is treated the same way, defensively: the only
 *    path that returns false is the proxy failing to read the PFD out of the request parcel (a
 *    same-build wire-invariant violation). Folding it in avoids decoding an empty reply parcel
 *    where `readInt()` returns 0 (which equals [TAG_OK]) and surfacing a phantom raster.
 *  - A malformed reply parcel — unrecognised tag, missing [SharedMemory] on an [TAG_OK] reply,
 *    or unrecognised wire code — also folds to [ImageDecodeRejectedKind.DecoderUnavailable]
 *    rather than throwing. The attacker-controlled reply shape is treated like the payload:
 *    never trusted. A throw out of this result-returning API would be a DoS on the decode
 *    path. Same-build wire mismatches are still caught fast by [ImageDecodeBinderRoundTripTest]
 *    and the wire-surface test.
 */
public class ImageDecodeClient(
    private val binder: IBinder,
) : ImageDecodeBinder {
    override suspend fun decode(
        image: ParcelFileDescriptor,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ): ImageDecodeResult =
        withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeTypedObject(image, 0)
                data.writeInt(maxWidthPx)
                data.writeInt(maxHeightPx)
                val accepted =
                    try {
                        binder.transact(CODE_DECODE, data, reply, 0)
                    } catch (_: RemoteException) {
                        return@withContext decoderUnavailable()
                    }
                if (!accepted) {
                    return@withContext decoderUnavailable()
                }
                // Untrusted reply (sender may be a compromised sandbox): any parse failure
                // folds to DecoderUnavailable instead of throwing out of this suspend result.
                runCatching { parseReply(reply) }.getOrElse { decoderUnavailable() }
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

    private fun parseReply(reply: Parcel): ImageDecodeResult =
        when (val tag = reply.readInt()) {
            TAG_OK ->
                ImageDecodeResult.Ok(
                    sharedMemory = reply.readTypedObject(SharedMemory.CREATOR) ?: error("Decode reply missing raster"),
                    widthPx = reply.readInt(),
                    heightPx = reply.readInt(),
                    sourceAspect = reply.readFloat(),
                )
            TAG_REJECTED -> ImageDecodeResult.Rejected(ImageDecodeRejectedKindWire.decode(reply.readInt()))
            else -> error("Unknown decode reply tag: $tag")
        }

    private fun decoderUnavailable(): ImageDecodeResult =
        ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecoderUnavailable)
}
