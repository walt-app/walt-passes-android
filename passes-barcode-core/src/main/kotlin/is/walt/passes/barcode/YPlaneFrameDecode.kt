package `is`.walt.passes.barcode

import com.google.zxing.PlanarYUVLuminanceSource
import `is`.walt.passes.core.BarcodeDecodeResult

/**
 * Pure-JVM live-camera frame entry point (wpass-7xo.5): decode a barcode/QR straight off a camera
 * luminance (Y) plane, reusing the same [decodeLuminance] core and symbology allowlist the static
 * still-image path uses. ONE decode implementation, ONE roster — the live path does not fork.
 *
 * NO CameraX / Android type crosses into this kernel. The consumer (walt-android, epic wlt-mwj)
 * extracts the Y plane from its `ImageProxy` / `ImageAnalysis` frame and passes raw bytes plus the
 * plane geometry in; the module stays KMP-friendly (`ByteArray` + `Int` only). The contract is
 * identical to the static path: the [ScannableFormat][`is`.walt.passes.core.ScannableFormat] roster
 * is enforced (PDF417/Aztec rejected), the payload is returned FAITHFULLY, and the result is only
 * [BarcodeDecodeResult] — `{payload, format}`, `NoBarcodeFound`, or `DecodeFailed`. Nothing here
 * interprets, classifies, or validates the bytes.
 *
 * ### Stride handling (ZXing #1387)
 * An Android YUV_420_888 Y plane is rarely densely packed. Two HAL realities have to be stripped
 * before ZXing sees the pixels, and getting them wrong feeds garbage rows into the binarizer:
 *  - **rowStride > width** — the common case: each row is padded out to a hardware alignment, so
 *    the real pixels occupy only the first [width] bytes of every [rowStride]-byte row. When the
 *    plane is otherwise contiguous (`pixelStride == 1`) this is stripped with ZERO copy by handing
 *    the buffer to [PlanarYUVLuminanceSource] with `dataWidth = rowStride`: it reads exactly [width]
 *    bytes per row starting at `row * rowStride`, skipping the trailing padding for free.
 *  - **pixelStride > 1** — some HALs interleave the Y samples (e.g. a semi-planar layout surfaced
 *    with `pixelStride == 2`). [PlanarYUVLuminanceSource] assumes one contiguous byte per pixel, so
 *    such a plane is first repacked into a tight `width * height` buffer.
 *
 * ### Signature decision
 * [rowStride] and [pixelStride] are exposed because real HALs vary them (wpass-7xo.3 spike); the
 * `byte[] + ints` shape absorbs the spike outcome without a contract change. Crop is NOT exposed in
 * v1 — the full plane is decoded and a crop overload can be added later without breaking callers.
 * [reverseHorizontal] is exposed (default `false`) for the front-camera mirrored case.
 *
 * Geometry preconditions (positive dimensions, `rowStride >= width`, a buffer large enough for the
 * stated layout) are caller-wiring errors, not decode outcomes, so they fail fast via [require]
 * rather than widening the [BarcodeDecodeResult] surface; the result arms stay reserved for what the
 * decode itself can produce.
 */
@Suppress("LongParameterList") // Each parameter is a distinct camera-plane fact; see "Signature decision".
public fun decodeYPlane(
    yPlane: ByteArray,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int = 1,
    reverseHorizontal: Boolean = false,
): BarcodeDecodeResult {
    require(width > 0 && height > 0) {
        "Frame dimensions must be positive (was ${width}x$height)."
    }
    require(pixelStride >= 1) { "pixelStride ($pixelStride) must be >= 1." }
    val rowSpan = width.toLong() * pixelStride
    require(rowStride >= rowSpan) {
        "rowStride ($rowStride) cannot be smaller than width * pixelStride ($rowSpan)."
    }
    // Largest byte the per-row reads reach: last row start + last pixel offset within the row.
    val maxIndex = (height - 1).toLong() * rowStride + (width - 1).toLong() * pixelStride
    require(maxIndex < yPlane.size) {
        "Y-plane buffer (${yPlane.size} bytes) too small for ${width}x$height " +
            "at rowStride=$rowStride pixelStride=$pixelStride."
    }

    val (data, dataWidth) =
        if (pixelStride == 1) {
            // Contiguous rows: hand ZXing the buffer as-is; dataWidth = rowStride strips padding.
            yPlane to rowStride
        } else {
            packTight(yPlane, width, height, rowStride, pixelStride) to width
        }

    val source =
        PlanarYUVLuminanceSource(
            data,
            dataWidth,
            height,
            0,
            0,
            width,
            height,
            reverseHorizontal,
        )
    return decodeLuminance(source)
}

/** Collapse a row/pixel-strided Y plane into a dense `width * height` luminance buffer. */
private fun packTight(
    yPlane: ByteArray,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
): ByteArray {
    val packed = ByteArray(width * height)
    var dst = 0
    for (row in 0 until height) {
        var src = row * rowStride
        repeat(width) {
            packed[dst++] = yPlane[src]
            src += pixelStride
        }
    }
    return packed
}
