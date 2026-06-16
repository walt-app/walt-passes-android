package `is`.walt.passes.barcode.android

import android.graphics.ImageDecoder
import android.os.ParcelFileDescriptor
import `is`.walt.passes.core.DecodeFailureReason
import `is`.walt.passes.image.decode.BoundedBitmap
import `is`.walt.passes.image.decode.BoundedDecodePolicy
import `is`.walt.passes.image.decode.decodeBounded
import java.io.ByteArrayOutputStream
import java.io.InputStream

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
 * Decode [rawBytes] under the [headerRejection] caps via the shared [decodeBounded] mechanism,
 * which fires the gate before the backing bitmap is allocated. This module's posture: a
 * software allocator so the symbol decode can read pixels (a hardware bitmap refuses
 * getPixels; this bitmap is analysed, never displayed), and containment of the decode
 * failures the platform raises ([OutOfMemoryError] bucketed as too-large), so this function
 * never throws for them. The platform-decode integration is the instrumented half
 * (wpass-zrt.5); the cap decision is unit-tested via [headerRejection].
 */
internal fun decodeBoundedBitmap(
    rawBytes: ByteArray,
    config: BarcodeDecodeConfig,
): BoundedDecodeResult =
    when (
        val decoded =
            decodeBounded(
                rawBytes = rawBytes,
                policy =
                    BoundedDecodePolicy(
                        allocator = ImageDecoder.ALLOCATOR_SOFTWARE,
                        gate = { mimeType, width, height -> headerRejection(mimeType, width, height, config) },
                        onMalformed = { DecodeFailureReason.ImageDecodeFailed },
                        onRuntimeFailure = { DecodeFailureReason.ImageDecodeFailed },
                        onOutOfMemory = { DecodeFailureReason.ImageTooLarge },
                    ),
            )
    ) {
        is BoundedBitmap.Decoded -> BoundedDecodeResult.Decoded(decoded.bitmap)
        is BoundedBitmap.Rejected -> BoundedDecodeResult.Rejected(decoded.reason)
    }

private const val DEFAULT_READ_CHUNK = 64 * 1024
