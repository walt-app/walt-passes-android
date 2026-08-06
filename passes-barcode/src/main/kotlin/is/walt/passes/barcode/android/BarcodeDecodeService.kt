package `is`.walt.passes.barcode.android

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import android.os.ParcelFileDescriptor
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.DecodeFailureReason
import java.io.ByteArrayOutputStream
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
 *
 * [onCreate] warms both decoders before `onBind` returns (see [warmDecodePath]) so the
 * watchdog budget bounds decode work rather than the sandbox's cold start.
 */
public class BarcodeDecodeService : Service() {
    private val config: BarcodeDecodeConfig = BarcodeDecodeConfig()
    private val watchdog: DecodeWatchdog = DecodeWatchdog(config.decodeTimeoutMs)
    private val symbolDecoder: BarcodeSymbolDecoder = ZxingBarcodeSymbolDecoder()

    override fun onCreate() {
        super.onCreate()
        warmDecodePath(config, symbolDecoder)
    }

    override fun onBind(intent: Intent): IBinder = BarcodeDecodeBinderProxy(buildImpl())

    private fun buildImpl(): BarcodeDecodeBinder =
        object : BarcodeDecodeBinder {
            override suspend fun decode(image: ParcelFileDescriptor): BarcodeDecodeResult =
                doDecode(image, config, watchdog, symbolDecoder)
        }
}

/**
 * Pay the sandbox's cold-start cost here, in `onCreate`, so [DecodeWatchdog]'s budget covers
 * decode work and nothing else (wpass-qw3). A freshly forked isolated process loads
 * `ImageDecoder`'s native codec support and every ZXing reader class on first touch,
 * interpreted and un-JIT'd; on a loaded machine that alone consumed most of the budget and the
 * watchdog killed benign decodes. Because the host has no bind timeout, moving the cost ahead
 * of `onBind` tightens what the guard bounds rather than loosening the guard.
 *
 * The probe is Walt-generated, never caller-supplied, and runs the production path end to end:
 * encode a blank bitmap, put it through [decodeBoundedBitmap], then through [symbolDecoder].
 * Sized past ZXing's 40px hybrid-binarizer floor so the real binarizer path warms too. Any
 * failure is swallowed — warm-up is an optimization, and a service that refuses to start would
 * turn a slow decode into no decode at all.
 */
internal fun warmDecodePath(
    config: BarcodeDecodeConfig,
    symbolDecoder: BarcodeSymbolDecoder,
) {
    runCatching {
        val probe = Bitmap.createBitmap(WARM_UP_PROBE_PX, WARM_UP_PROBE_PX, Bitmap.Config.ARGB_8888)
        try {
            val encoded = ByteArrayOutputStream()
            probe.compress(Bitmap.CompressFormat.PNG, 100, encoded)
            // Feed the symbol decoder the ImageDecoder-produced bitmap, exactly as doDecode
            // does; handing it the source probe instead would skip a hop of the real path.
            when (val decoded = decodeBoundedBitmap(encoded.toByteArray(), config)) {
                is BoundedDecodeResult.Decoded ->
                    try {
                        symbolDecoder.decode(decoded.bitmap)
                    } finally {
                        decoded.bitmap.recycle()
                    }
                // The platform decoder is unavailable (the JVM test runtime, say). Warm ZXing
                // off the probe rather than skipping it — the reader classes are the slow half.
                is BoundedDecodeResult.Rejected -> symbolDecoder.decode(probe)
            }
        } finally {
            probe.recycle()
        }
    }
}

/** Above ZXing's 40px floor for the hybrid binarizer, so warm-up touches the real path. */
private const val WARM_UP_PROBE_PX = 64

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
