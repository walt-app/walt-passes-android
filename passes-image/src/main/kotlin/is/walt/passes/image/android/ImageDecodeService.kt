package `is`.walt.passes.image.android

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlin.coroutines.cancellation.CancellationException

/**
 * The isolated-process image-decode service (wpass-6yp, step 3 of wpass-i9x) — THE security
 * gate for decoding hostile user images. Declared in this module's manifest with
 * `android:isolatedProcess="true"`, `android:exported="false"`, and zero `uses-permission`
 * entries, it runs under an isolated UID with no INTERNET, no storage, no clipboard, no
 * Keystore, no card material — and cannot reach any of them even after a codec compromise. A
 * decoder use-after-free here brings down this process, not the wallet, and the isolated
 * sandbox guarantees the compromised process has nothing useful to reach for in the moment
 * before it dies. This is the same posture as `passes-pdf`'s `PdfRendererService` and
 * `passes-barcode`'s `BarcodeDecodeService`.
 *
 * The service exposes exactly one binder transaction ([ImageDecodeBinder.decode]); there is
 * intentionally no method to extract a `Bitmap` the caller hands in, the source bytes, or
 * image metadata. The only thing that crosses back is a bounded, Walt-produced raster over
 * [android.os.SharedMemory] — never the original image. [ImageDecodeBinderSurfaceTest] asserts
 * the surface by reflection.
 *
 * The image reaches this process only as a [ParcelFileDescriptor] handed across the bind by
 * the shared `passes-isolation` facade — never as a path (an isolated UID has no filesystem to
 * wander) and never through the caller's main-process heap. The bytes are read only here,
 * inside the sandbox.
 *
 * Unlike `PdfRendererService` there is no SDK-34 floor: the decode rests on `ImageDecoder`
 * (API 28, the repo-wide `minSdk`) rather than the Mainline PDFium reachable through
 * `PdfRenderer`, so the sandbox runs on every supported device. The decode runs under a
 * [DecodeWatchdog] that kills the process on a slow/hung input (see [doDecode]).
 */
public class ImageDecodeService : Service() {
    private val config: ImageDecodeConfig = ImageDecodeConfig()
    private val watchdog: DecodeWatchdog = DecodeWatchdog(config.decodeTimeoutMs)

    override fun onBind(intent: Intent): IBinder = ImageDecodeBinderProxy(buildImpl())

    private fun buildImpl(): ImageDecodeBinder =
        object : ImageDecodeBinder {
            override suspend fun decode(
                image: ParcelFileDescriptor,
                maxWidthPx: Int,
                maxHeightPx: Int,
            ): ImageDecodeResult = doDecode(image, maxWidthPx, maxHeightPx, config, watchdog)
        }
}

/**
 * One decode: read and bound-decode [image] to a bounded raster under [watchdog], close the
 * descriptor, and return only the Walt-produced [ImageDecodeResult]. Top-level and
 * seam-injected so the orchestration is unit-testable without a live isolated process; the
 * production service passes [ImageDecodeConfig] and a real [DecodeWatchdog]. [boundedDecode]
 * defaults to the real [decodeRasterFromPfd]; tests substitute it so the orchestration runs
 * without the platform image codec.
 *
 * Containment is total: a bounded-decode rejection becomes an [ImageDecodeResult.Rejected] with
 * the bucketed kind, and any Throwable that escapes the inner decode (the watchdog has already
 * handled the *hang* case by killing the process) folds to [ImageDecodeRejectedKind.DecodeFailed]
 * rather than crashing the sandbox uncleanly. The descriptor is closed on every path. No
 * payload, bytes, or image metadata is logged — the function emits nothing.
 */
@Suppress("LongParameterList")
internal suspend fun doDecode(
    image: ParcelFileDescriptor,
    maxWidthPx: Int,
    maxHeightPx: Int,
    config: ImageDecodeConfig,
    watchdog: DecodeWatchdog,
    boundedDecode: (ParcelFileDescriptor, Int, Int, ImageDecodeConfig) -> ImageDecodeResult = ::decodeRasterFromPfd,
): ImageDecodeResult =
    try {
        watchdog.guard { boundedDecode(image, maxWidthPx, maxHeightPx, config) }
    } catch (e: CancellationException) {
        // Never fold cancellation into a result — let structured concurrency unwind.
        throw e
    } catch (_: Throwable) {
        ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecodeFailed)
    } finally {
        runCatching { image.close() }
    }
