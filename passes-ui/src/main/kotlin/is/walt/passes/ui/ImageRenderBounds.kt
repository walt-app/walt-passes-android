package `is`.walt.passes.ui

/**
 * Hard upper bounds for decoded image dimensions, applied at decode time by the
 * `BoundedImage` composable. Prevents a malformed or hostile pass archive from
 * forcing the decoder to allocate a multi-gigabyte bitmap (a "decompression bomb"
 * via, e.g., a maliciously huge `background.png`).
 *
 * The PKPASS spec's recommended sizes are well under any of these caps; legitimate
 * passes never approach them. The caps exist as a backstop for the case where the
 * archive's declared image is larger than the spec allows or has been crafted to
 * exhaust memory.
 *
 * Values are pre-scale: the decoder downsamples to fit `maxWidthPx` x `maxHeightPx`
 * before producing a bitmap. `maxAreaPx` is enforced after downsample as a final
 * guard against extreme aspect ratios that pass the per-axis caps individually but
 * still produce a too-large surface.
 *
 * Defaults derive from the maximum image dimensions Apple's PKPASS guidance lists
 * for `background@3x.png` (the largest legal asset), with a 2× safety margin.
 */
public data class ImageRenderBounds(
    public val maxWidthPx: Int,
    public val maxHeightPx: Int,
    public val maxAreaPx: Long,
) {
    init {
        require(maxWidthPx > 0) { "maxWidthPx must be positive, was $maxWidthPx" }
        require(maxHeightPx > 0) { "maxHeightPx must be positive, was $maxHeightPx" }
        require(maxAreaPx > 0L) { "maxAreaPx must be positive, was $maxAreaPx" }
    }

    public companion object {
        /**
         * Default backstop: 1920 x 1920 pixels, 4 megapixels of total surface. Apple's
         * largest documented PKPASS asset (`background@3x.png` at 360 x 220) is well
         * under both per-axis caps; this default exists to bound a hostile archive,
         * not legitimate content.
         */
        public val Default: ImageRenderBounds = ImageRenderBounds(
            maxWidthPx = 1920,
            maxHeightPx = 1920,
            maxAreaPx = 4_000_000L,
        )
    }
}
