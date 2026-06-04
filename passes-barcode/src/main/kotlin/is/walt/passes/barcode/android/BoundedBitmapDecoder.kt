package `is`.walt.passes.barcode.android

import android.graphics.ImageDecoder
import android.os.ParcelFileDescriptor
import `is`.walt.passes.core.DecodeFailureReason
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Decodes a candidate image to a [Bitmap] *inside* the `:barcodeDecoder` sandbox with the
 * wpass-zrt.3 caps applied before the platform decoder allocates a full-size bitmap. This is
 * the dominant hostile-input surface (CVE-2023-4863 libwebp / CVE-2020-16010 class), so it
 * runs only here — never in the caller's process — and every cap is a pre-allocation gate.
 *
 * The decode is the same `ImageDecoder` + `OnHeaderDecodedListener` lever `passes-ui`'s
 * `BoundedImage` uses, but for analysis rather than display: `BoundedImage` is display-only
 * and capped-raster, so its bitmap cannot be reused to find barcode pixels. The produced
 * bitmap stays inside the sandbox; the ZXing symbol decode (wpass-zrt.4) consumes it here and
 * only `{payload, format}` ever crosses back over the binder.
 *
 * Read [pfd] into a bounded byte array and decode it under [config]. The read goes through a
 * `dup()` so the stream's `close()` releases only the duplicate and [pfd] survives for its
 * single owner ([doDecode]) to close — the same idiom as the PDF importer, avoiding a
 * double-close of the source fd. Pulling the compressed bytes into the sandbox heap is fine:
 * isolation keeps them off the *caller's* heap; the file-size cap bounds how many can be read.
 *
 * Stays a top-level function (not a class) so its two phases are unit-testable without a live
 * service: [readBoundedBytes] against a pipe, and [headerRejection] against the cap table.
 */
internal fun decodeBoundedFromPfd(
    pfd: ParcelFileDescriptor,
    config: BarcodeDecodeConfig,
): BoundedDecodeResult {
    val read =
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(pfd.dup()).use { readBoundedBytes(it, config.maxBytes) }
        }
    val bytes = read.getOrNull()
    return when {
        read.isFailure -> BoundedDecodeResult.Rejected(DecodeFailureReason.SourceUnreadable)
        bytes == null -> BoundedDecodeResult.Rejected(DecodeFailureReason.ImageTooLarge)
        else -> decodeBoundedBitmap(bytes, config)
    }
}

/**
 * Read at most [maxBytes] from [input], returning null the moment the source exceeds the cap
 * (a large-file decompression bomb) so no oversized buffer is ever fully read. Reads stop
 * early on the first over-cap byte rather than draining the whole stream.
 */
internal fun readBoundedBytes(input: InputStream, maxBytes: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_READ_CHUNK)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > maxBytes) return null
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/**
 * The pure header-cap decision: given the container [mimeType] and decoded-but-not-allocated
 * [width]/[height] reported to the `OnHeaderDecodedListener`, return the reason to reject, or
 * null to proceed. Extracted from [decodeBoundedBitmap] so the cap math and the format
 * allowlist are unit-testable without driving the platform decoder (whose native decode the
 * JVM test runtime cannot exercise — that half is the instrumented suite, wpass-zrt.5).
 *
 * A container outside [BarcodeDecodeConfig.allowedMimeTypes] folds to
 * [DecodeFailureReason.ImageDecodeFailed]: from the caller's view an unsupported container is
 * simply one this decoder will not turn into pixels. Over-dimension or over-area folds to
 * [DecodeFailureReason.ImageTooLarge] — the decompression-bomb bucket.
 */
internal fun headerRejection(
    mimeType: String?,
    width: Int,
    height: Int,
    config: BarcodeDecodeConfig,
): DecodeFailureReason? =
    when {
        mimeType !in config.allowedMimeTypes -> DecodeFailureReason.ImageDecodeFailed
        width > config.maxDimensionPx || height > config.maxDimensionPx -> DecodeFailureReason.ImageTooLarge
        width.toLong() * height.toLong() > config.maxAreaPx -> DecodeFailureReason.ImageTooLarge
        else -> null
    }

/**
 * Decode [rawBytes] under the [headerRejection] caps, which fire before the backing bitmap is
 * allocated. Full Throwable containment — including [OutOfMemoryError] — folds every escape to
 * a [BoundedDecodeResult.Rejected]; this function never throws. The platform-decode integration
 * is the instrumented half (wpass-zrt.5); the cap decision is unit-tested via [headerRejection].
 */
internal fun decodeBoundedBitmap(
    rawBytes: ByteArray,
    config: BarcodeDecodeConfig,
): BoundedDecodeResult {
    val source = ImageDecoder.createSource(ByteBuffer.wrap(rawBytes))
    var rejection: DecodeFailureReason? = null
    return try {
        val bitmap =
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // Software-backed so the symbol decode can read pixels; a hardware bitmap (the
                // default) refuses getPixels. This bitmap is analysed, never displayed.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                rejection = headerRejection(info.mimeType, info.size.width, info.size.height, config)
                if (rejection != null) {
                    // Force a 1x1 decode so the rejected path allocates nothing of size; the
                    // bitmap is discarded below.
                    decoder.setTargetSize(1, 1)
                }
            }
        rejection?.let {
            bitmap.recycle()
            BoundedDecodeResult.Rejected(it)
        } ?: BoundedDecodeResult.Decoded(bitmap)
    } catch (_: IOException) {
        // Genuine parse failure, unless a bounds/format rejection was already in flight.
        BoundedDecodeResult.Rejected(rejection ?: DecodeFailureReason.ImageDecodeFailed)
    } catch (_: IllegalArgumentException) {
        // setTargetSize(1, 1) can throw for formats that refuse arbitrary sizing; the
        // rejection that triggered it already fired in the listener, so preserve it.
        BoundedDecodeResult.Rejected(rejection ?: DecodeFailureReason.ImageDecodeFailed)
    } catch (_: OutOfMemoryError) {
        // A canvas that slipped the header caps (or a pathological parse) must not take the
        // process down uncontained; bucket it as too-large.
        BoundedDecodeResult.Rejected(rejection ?: DecodeFailureReason.ImageTooLarge)
    } catch (_: RuntimeException) {
        BoundedDecodeResult.Rejected(rejection ?: DecodeFailureReason.ImageDecodeFailed)
    }
}

private const val DEFAULT_READ_CHUNK = 64 * 1024
