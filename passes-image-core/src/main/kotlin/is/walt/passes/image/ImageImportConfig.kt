package `is`.walt.passes.image

/**
 * Defensive limits and the telemetry hook applied during image import. The defaults pin
 * hard caps: 10 MB total, 4096 px on either dimension. They are exposed as constants so
 * consumers and tests refer to the same numbers, and so changing a default is a
 * deliberate, test-breaking edit.
 *
 * [maxBytes] is checked against the input source length before any decoding work.
 * [maxDimensionPx] is checked after a bounds-only decode (`inJustDecodeBounds`) but
 * before the full decode, so an oversized image is rejected without materialising its
 * full uncompressed bitmap in memory.
 */
public data class ImageImportConfig(
    public val maxBytes: Long = DEFAULT_MAX_BYTES,
    public val maxDimensionPx: Int = DEFAULT_MAX_DIMENSION_PX,
    public val telemetryGuard: ImageTelemetryGuard = ImageTelemetryGuard.NoOp,
) {
    public companion object {
        public const val DEFAULT_MAX_BYTES: Long = 10L * 1024 * 1024
        public const val DEFAULT_MAX_DIMENSION_PX: Int = 4096
    }
}
