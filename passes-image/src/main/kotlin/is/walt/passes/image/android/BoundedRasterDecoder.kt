package `is`.walt.passes.image.android

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import `is`.walt.passes.image.decode.BoundedBitmap
import `is`.walt.passes.image.decode.BoundedDecodePolicy
import `is`.walt.passes.image.decode.decodeBounded
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * Decodes an original user image to a bounded, Walt-produced ARGB_8888 raster *inside* the
 * `:imageDecoder` sandbox (wpass-6yp), with the wpass-i9x caps applied before the platform
 * decoder allocates a full-size bitmap. This is the dominant hostile-input surface
 * (CVE-2023-4863 libwebp / CVE-2020-16010 class), so it runs only here — never in the
 * caller's process — and every cap is a pre-allocation gate.
 *
 * The decode is the same `ImageDecoder` + `OnHeaderDecodedListener` lever (via the shared
 * `passes-image-decode` [decodeBounded]) that `passes-barcode` uses for symbol analysis and
 * `passes-ui` uses for in-process display, but here the produced bitmap is rescaled to a
 * bounded output and copied into [SharedMemory]; the source bytes and the full-size bitmap
 * never leave the sandbox. Only the rescaled pixel buffer crosses back over the binder.
 *
 * Read [image] into a bounded byte array through a `dup()` so the stream's `close()` releases
 * only the duplicate and [image] survives for its single owner ([doDecode]) to close — the
 * same idiom as the barcode decoder and the PDF importer, avoiding a double-close of the
 * source fd. Pulling the compressed bytes into the sandbox heap is fine: isolation keeps them
 * off the *caller's* heap; the file-size cap bounds how many can be read.
 *
 * Stays a top-level function (not a class) so its phases are unit-testable without a live
 * service: [readBoundedBytes] against a pipe, [headerRejection] against the cap table, and
 * [outputDims] against the scaling math.
 */
internal fun decodeRasterFromPfd(
    image: ParcelFileDescriptor,
    maxWidthPx: Int,
    maxHeightPx: Int,
    config: ImageDecodeConfig,
): ImageDecodeResult {
    // Output-bound check before reading or allocating anything: a caller asking for a
    // non-positive or over-cap output raster is refused up front (defence against the host
    // requesting an arbitrarily large bitmap on the sandbox side; mirrors PdfRendererService).
    if (!isOutputSizeValid(maxWidthPx, maxHeightPx, config.maxOutputPixels)) {
        return ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecodeFailed)
    }
    val read =
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(image.dup()).use { readBoundedBytes(it, config.maxBytes) }
        }
    val bytes = read.getOrNull()
    return when {
        read.isFailure -> ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecodeFailed)
        bytes == null -> ImageDecodeResult.Rejected(ImageDecodeRejectedKind.OversizedAtImport)
        else -> decodeBoundedRaster(bytes, maxWidthPx, maxHeightPx, config)
    }
}

internal fun isOutputSizeValid(maxWidthPx: Int, maxHeightPx: Int, maxOutputPixels: Long): Boolean =
    maxWidthPx > 0 && maxHeightPx > 0 && maxWidthPx.toLong() * maxHeightPx.toLong() <= maxOutputPixels

/**
 * Read at most [maxBytes] from [input], returning null the moment the source exceeds the cap
 * (a large-file decompression bomb) so no oversized buffer is ever fully read. Reads stop
 * early on the first over-cap byte rather than draining the whole stream. Identical shape to
 * `passes-barcode`'s `readBoundedBytes`.
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
 * null to proceed. Extracted so the cap math and the format allowlist are unit-testable
 * without driving the platform decoder (whose native decode the JVM test runtime cannot
 * exercise — that half is the instrumented suite).
 *
 * A container outside [ImageDecodeConfig.allowedMimeTypes] folds to
 * [ImageDecodeRejectedKind.NotAnImage]: from the caller's view an unsupported container is one
 * this decoder will not turn into pixels. Over-dimension or over-area folds to
 * [ImageDecodeRejectedKind.DimensionsTooLarge] — the decompression-bomb bucket.
 */
internal fun headerRejection(
    mimeType: String?,
    width: Int,
    height: Int,
    config: ImageDecodeConfig,
): ImageDecodeRejectedKind? =
    when {
        mimeType !in config.allowedMimeTypes -> ImageDecodeRejectedKind.NotAnImage
        width > config.maxDimensionPx || height > config.maxDimensionPx -> ImageDecodeRejectedKind.DimensionsTooLarge
        width.toLong() * height.toLong() > config.maxAreaPx -> ImageDecodeRejectedKind.DimensionsTooLarge
        else -> null
    }

/**
 * Output raster dimensions: an aspect-preserving fit of the source [srcW] x [srcH] into the
 * requested [maxWidthPx] x [maxHeightPx] bound, never upscaled past the source (a tiny image
 * is returned at native size and the host scales it up cheaply for display). The source's own
 * aspect ratio is preserved, so the raster is never stretched at the source — the host applies
 * `ContentScale.Fit` and letterboxes inside its slot, exactly as the PDF page surface does.
 */
internal fun outputDims(srcW: Int, srcH: Int, maxWidthPx: Int, maxHeightPx: Int): OutputDims {
    val fit = minOf(maxWidthPx.toFloat() / srcW, maxHeightPx.toFloat() / srcH, 1f)
    val w = (srcW * fit).roundToInt().coerceIn(1, maxWidthPx)
    val h = (srcH * fit).roundToInt().coerceIn(1, maxHeightPx)
    return OutputDims(w, h)
}

internal data class OutputDims(val widthPx: Int, val heightPx: Int)

/**
 * Decode [rawBytes] under the [headerRejection] caps via the shared [decodeBounded] mechanism,
 * which fires the gate before the backing bitmap is allocated, then rescale to the bounded
 * output and pack into [SharedMemory]. Posture: a software allocator so the pixels can be read
 * back into the SharedMemory buffer (a hardware bitmap refuses `copyPixelsToBuffer`), and
 * containment of the decode failures the platform raises (OOM bucketed as a decompression-bomb
 * rejection). The platform-decode integration is the instrumented half; the cap decision and
 * the scaling math are unit-tested via [headerRejection] / [outputDims].
 */
internal fun decodeBoundedRaster(
    rawBytes: ByteArray,
    maxWidthPx: Int,
    maxHeightPx: Int,
    config: ImageDecodeConfig,
): ImageDecodeResult =
    when (
        val decoded =
            decodeBounded(
                rawBytes = rawBytes,
                policy =
                    BoundedDecodePolicy(
                        allocator = ImageDecoder.ALLOCATOR_SOFTWARE,
                        gate = { mimeType, width, height -> headerRejection(mimeType, width, height, config) },
                        onMalformed = { ImageDecodeRejectedKind.NotAnImage },
                        onRuntimeFailure = { ImageDecodeRejectedKind.DecodeFailed },
                        onOutOfMemory = { ImageDecodeRejectedKind.DimensionsTooLarge },
                    ),
            )
    ) {
        is BoundedBitmap.Rejected -> ImageDecodeResult.Rejected(decoded.reason)
        is BoundedBitmap.Decoded -> packScaledRaster(decoded.bitmap, maxWidthPx, maxHeightPx)
    }

/**
 * Rescale [bitmap] to the bounded output and copy it into a read-only [SharedMemory]. Owns and
 * recycles both the source bitmap and any scaled intermediate before returning, so no native
 * pixel buffer outlives this call inside the sandbox. A throw here (e.g. an OOM during the
 * scale, or a SharedMemory allocation failure) propagates to [doDecode]'s containment, which
 * folds it to [ImageDecodeRejectedKind.DecodeFailed].
 */
private fun packScaledRaster(bitmap: Bitmap, maxWidthPx: Int, maxHeightPx: Int): ImageDecodeResult {
    val sourceAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    val dims = outputDims(bitmap.width, bitmap.height, maxWidthPx, maxHeightPx)
    val scaled =
        if (dims.widthPx == bitmap.width && dims.heightPx == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, dims.widthPx, dims.heightPx, true)
        }
    return try {
        ImageDecodeResult.Ok(
            sharedMemory = packIntoSharedMemory(scaled, dims.widthPx, dims.heightPx),
            widthPx = dims.widthPx,
            heightPx = dims.heightPx,
            sourceAspect = sourceAspect,
        )
    } finally {
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
    }
}

private fun packIntoSharedMemory(bitmap: Bitmap, widthPx: Int, heightPx: Int): SharedMemory {
    val byteCount = widthPx * heightPx * BYTES_PER_PIXEL
    val sm = SharedMemory.create("walt-image-decode", byteCount)
    val buf = sm.mapReadWrite()
    try {
        bitmap.copyPixelsToBuffer(buf)
    } finally {
        SharedMemory.unmap(buf)
    }
    sm.setProtect(OsConstants.PROT_READ)
    return sm
}

private const val DEFAULT_READ_CHUNK = 64 * 1024
private const val BYTES_PER_PIXEL = 4
