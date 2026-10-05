package `is`.walt.passes.isolation

/**
 * True when an ARGB_8888 raster a sandbox claims to have written is safe for the host to
 * allocate and copy: positive dims within the requested bound, a pixel count within
 * [maxPixels], and a buffer of [bufferBytes] large enough to hold every pixel.
 */
@Suppress("LongParameterList")
public fun rasterReplyFits(
    widthPx: Int,
    heightPx: Int,
    maxWidthPx: Int,
    maxHeightPx: Int,
    maxPixels: Long,
    bufferBytes: Long,
): Boolean {
    val pixels = widthPx.toLong() * heightPx.toLong()
    return widthPx in 1..maxWidthPx &&
        heightPx in 1..maxHeightPx &&
        pixels <= maxPixels &&
        // Divide, not multiply: pixels * 4 can overflow for a hostile claim.
        bufferBytes / ARGB_8888_BYTES_PER_PIXEL >= pixels
}

private const val ARGB_8888_BYTES_PER_PIXEL = 4L
