package `is`.walt.passes.barcode.android

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
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
 *    decode process that went away — including the [DecodeWatchdog] killing the sandbox on
 *    budget expiry. Which of the two it was is decided here, by elapsed time (wpass-qw3): the
 *    sandbox SIGKILLs itself, so it cannot report its own timeout, and the only observable
 *    that separates "the decoder was never there" from "the decoder ran out of time" is
 *    whether the full [decodeBudgetMs] elapsed before the binder dropped. At or past the
 *    budget folds to [DecodeFailureReason.DecodeTimedOut] (a load signal — the same image may
 *    decode fine on retry); anything earlier stays [DecodeFailureReason.DecoderUnavailable]
 *    (a crash or an absent decoder — retry will not help). What that comparison rests on is
 *    documented on [decoderWentAway].
 *  - A `false` return from [IBinder.transact] folds to [DecodeFailureReason.DecoderUnavailable]
 *    defensively, and is never timeout-attributed: the only path that returns false is the
 *    proxy failing to read the PFD out of the request parcel (a same-build wire-invariant
 *    violation), which is immediate and means no decode was ever attempted. Folding it in
 *    avoids decoding an empty reply parcel where `readInt()` returns 0 (which equals
 *    [TAG_DECODED]) and surfacing a phantom decoded result.
 *  - A malformed reply parcel — unrecognised tag, missing payload on a [TAG_DECODED] reply,
 *    or unrecognised wire code — also folds to [DecodeFailureReason.DecoderUnavailable]
 *    rather than throwing, and is likewise never timeout-attributed: the sandbox answered, so
 *    whatever went wrong is not a timeout regardless of how long it took. This is the one place
 *    the posture diverges from `passes-pdf`'s fail-fast `PdfRendererClient`: here the reply's
 *    sender is the isolated decode process,
 *    which this feature's threat model assumes may be compromised, so the reply shape is
 *    attacker-controlled and must be treated like the payload string — never trusted. A
 *    throw out of this result-returning API would be a DoS on the decode path; folding keeps
 *    the contract. Same-build wire mismatches are still caught fast by
 *    [BarcodeDecodeBinderRoundTripTest] and the two wire-surface tests.
 *
 * `internal`, not `public`, for the same reason as [BarcodeDecodeBinder]: no consumer outside
 * this module references the client; [BarcodeImageDecoder] is the only entry point.
 */
internal class BarcodeDecodeClient(
    private val binder: IBinder,
    private val decodeBudgetMs: Long = BarcodeDecodeConfig.DEFAULT_DECODE_TIMEOUT_MS,
    private val elapsedMs: () -> Long = SystemClock::uptimeMillis,
) : BarcodeDecodeBinder {
    override suspend fun decode(image: ParcelFileDescriptor): BarcodeDecodeResult =
        withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeTypedObject(image, 0)
                // Stamped immediately before the transaction: the elapsed window must cover the
                // decode only, not the bind that preceded it (which pays sandbox cold start).
                val startedAtMs = elapsedMs()
                val accepted =
                    try {
                        binder.transact(CODE_DECODE, data, reply, 0)
                    } catch (_: RemoteException) {
                        return@withContext decoderWentAway(startedAtMs)
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

    private fun parseReply(reply: Parcel): BarcodeDecodeResult =
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

    /**
     * The binder dropped mid-transaction. Attribute it by how long the decode had been running:
     * the sandbox's own watchdog kills at [decodeBudgetMs], so reaching that mark is the
     * signature of a timeout, while an earlier death is a crash or an absent decoder.
     *
     * Two things have to hold for that comparison to mean anything, and both are load-bearing:
     *
     *  - Same VALUE. [decodeBudgetMs] defaults to the [BarcodeDecodeConfig] field the sandbox
     *    arms its watchdog from, so neither side can be retuned without the other.
     *  - Same CLOCK. [SystemClock.uptimeMillis] shares `CLOCK_MONOTONIC` with the `delay` the
     *    watchdog's kill timer runs on, so both stop counting across device deep sleep.
     *    `elapsedRealtime` would keep counting through a suspend the sandbox never experienced
     *    and could report a timeout the watchdog never fired.
     */
    private fun decoderWentAway(startedAtMs: Long): BarcodeDecodeResult =
        if (elapsedMs() - startedAtMs >= decodeBudgetMs) {
            BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.DecodeTimedOut)
        } else {
            decoderUnavailable()
        }

    private fun decoderUnavailable(): BarcodeDecodeResult =
        BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.DecoderUnavailable)
}
