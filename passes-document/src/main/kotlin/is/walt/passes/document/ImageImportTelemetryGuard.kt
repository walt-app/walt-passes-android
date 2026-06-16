package `is`.walt.passes.document

/**
 * Hook for emitting image-import observability events from [DocumentImporter]'s image arm.
 * The PDF arm reports through `passes-pdf-core`'s `DocumentTelemetryGuard` (carried on
 * [DocumentImportConfig.pdfConfig]); this is its image-side counterpart, salvaged from PR
 * #146's `ImageTelemetryGuard`.
 *
 * The shape of the event types is the load-bearing security control: every parameter is an
 * enum, a count, or a duration. There is no [String] carrying a filename, no [ByteArray]
 * carrying file contents, no map of free-form attributes. That structural restriction is the
 * trust claim mirroring `passes-core`'s `TelemetryGuard` and `passes-pdf-core`'s
 * `DocumentTelemetryGuard`: image content and identifying metadata never appear in logs or
 * telemetry, by interface construction. Reviewers should treat any future addition of a
 * `String` / `CharSequence` / `ByteArray` / `Map` parameter as a security-policy change.
 */
public interface ImageImportTelemetryGuard {
    public fun onImportStarted()

    public fun onImportSucceeded(event: ImageImportSucceededEvent)

    public fun onImportFailed(event: ImageImportFailedEvent)

    public object NoOp : ImageImportTelemetryGuard {
        override fun onImportStarted(): Unit = Unit

        override fun onImportSucceeded(event: ImageImportSucceededEvent): Unit = Unit

        override fun onImportFailed(event: ImageImportFailedEvent): Unit = Unit
    }
}

public data class ImageImportSucceededEvent(
    public val byteCount: Long,
    public val format: ImageFormat,
    public val widthPx: Int,
    public val heightPx: Int,
    public val durationMillis: Long,
)

public data class ImageImportFailedEvent(
    public val outcome: ImageImportFailureKind,
    public val durationMillis: Long,
)

/**
 * The telemetry-safe outcome of a failed image import. Fired only once the importer has
 * committed to the image arm (the bytes sniffed as a supported image); a top-level
 * [DocumentImportResult.Unrecognized] is not an image attempt and emits no image telemetry.
 * Carries no string payload:
 *
 *  - [Decode] — the isolated decode rejected the image (the precise
 *    [ImageDecodeRejectedKind][`is`.walt.passes.image.android.ImageDecodeRejectedKind] is on
 *    the returned [DocumentImportResult]; telemetry keeps only this coarse bucket so a decoder
 *    error string can never leak here).
 *  - [StorageHandoff] — the consumer's `persist` callback threw after a successful decode.
 */
public enum class ImageImportFailureKind {
    Decode,
    StorageHandoff,
}
