package `is`.walt.passes.barcode.android

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.DecodeFailureReason
import kotlin.coroutines.cancellation.CancellationException

/**
 * The isolated-process decode service (wpass-zrt.2) — THE security gate for hostile-image
 * decoding. Declared in this module's manifest with `android:isolatedProcess="true"`,
 * `android:exported="false"`, and zero `uses-permission` entries, it runs under an isolated
 * UID with no INTERNET, no storage, no clipboard, no Keystore, no DPAN/card material — and
 * cannot reach any of them even after a codec compromise (the contractual go/no-go boundary
 * inherited from walt-android wlt-58a.1). A decoder use-after-free here brings down this
 * process, not the wallet, and the isolated sandbox guarantees the compromised process has
 * nothing useful to reach for in the moment before it dies.
 *
 * The service exposes exactly one binder transaction ([BarcodeDecodeBinder.decode]); there
 * is intentionally no method to extract a `Bitmap`, the source bytes, or image metadata.
 * [BarcodeDecodeBinderSurfaceTest] asserts the surface by reflection.
 *
 * The image reaches this process only as a [ParcelFileDescriptor] handed across the bind by
 * the shared `passes-isolation` facade — never as a path (an isolated UID has no filesystem
 * to wander) and never through the caller's main-process heap. The bytes are read only
 * here, inside the sandbox.
 *
 * The decode itself runs in two composed steps (see [doDecode]): the bounded codec decode
 * (wpass-zrt.3) caps file size, container format, and canvas dimensions before the platform
 * decoder allocates, under a [DecodeWatchdog] that kills the process on a slow/hung input;
 * the symbol decode (wpass-zrt.4) reads the barcode off the produced bitmap. Only the pure
 * `{payload, format}` result crosses back — the bitmap is recycled inside the sandbox.
 */
public class BarcodeDecodeService : Service() {
    private val config: BarcodeDecodeConfig = BarcodeDecodeConfig()
    private val watchdog: DecodeWatchdog = DecodeWatchdog(config.decodeTimeoutMs)

    override fun onBind(intent: Intent): IBinder = BarcodeDecodeBinderProxy(buildImpl())

    private fun buildImpl(): BarcodeDecodeBinder =
        object : BarcodeDecodeBinder {
            override suspend fun decode(image: ParcelFileDescriptor): BarcodeDecodeResult =
                doDecode(image, config, watchdog, BarcodeSymbolDecoder.NotYetImplemented)
        }
}

/**
 * One decode: read and bound-decode [image] to a bitmap under [watchdog], hand the bitmap to
 * [symbolDecoder], recycle it, and close the descriptor. Top-level and seam-injected so the
 * orchestration is unit-testable without a live isolated process; the production service
 * passes [BarcodeDecodeConfig], a real [DecodeWatchdog], and the ZXing decoder. [boundedDecode]
 * defaults to the real [decodeBoundedFromPfd]; tests substitute it so the orchestration runs
 * without the platform image codec.
 *
 * Containment is total: a bounded-decode rejection becomes a [BarcodeDecodeResult.DecodeFailed]
 * with the bucketed reason, and any Throwable that escapes the inner decode (the watchdog has
 * already handled the *hang* case by killing the process) folds to
 * [DecodeFailureReason.ImageDecodeFailed] rather than crashing the sandbox uncleanly. The
 * descriptor is closed on every path. No payload, bytes, or image metadata is logged — the
 * function emits nothing.
 */
internal suspend fun doDecode(
    image: ParcelFileDescriptor,
    config: BarcodeDecodeConfig,
    watchdog: DecodeWatchdog,
    symbolDecoder: BarcodeSymbolDecoder,
    boundedDecode: (ParcelFileDescriptor, BarcodeDecodeConfig) -> BoundedDecodeResult = ::decodeBoundedFromPfd,
): BarcodeDecodeResult =
    try {
        watchdog.guard {
            when (val decoded = boundedDecode(image, config)) {
                is BoundedDecodeResult.Rejected -> BarcodeDecodeResult.DecodeFailed(decoded.reason)
                is BoundedDecodeResult.Decoded ->
                    try {
                        symbolDecoder.decode(decoded.bitmap)
                    } finally {
                        decoded.bitmap.recycle()
                    }
            }
        }
    } catch (e: CancellationException) {
        // Never fold cancellation into a result — let structured concurrency unwind.
        throw e
    } catch (_: Throwable) {
        BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.ImageDecodeFailed)
    } finally {
        runCatching { image.close() }
    }
