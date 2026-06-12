package `is`.walt.passes.image

/**
 * Hook for emitting image-import observability events to a host telemetry pipeline. The
 * shape of the event types is the load-bearing security control here: every parameter is
 * either an enum, a count, or a duration. There is no [String] carrying filename, no
 * [ByteArray] carrying file contents, no map of free-form attributes.
 *
 * That structural restriction is the trust claim mirroring `passes-core`'s
 * `TelemetryGuard` and `passes-pdf-core`'s `DocumentTelemetryGuard`: image content and
 * identifying metadata never appear in logs or telemetry, by interface construction. A
 * consumer cannot accidentally log a filename through this interface because the
 * interface refuses to accept one. Reviewers should treat any future addition of a
 * [String] / [CharSequence] / [ByteArray] / [Map] parameter to these events as a
 * security-policy change requiring re-review.
 */
public interface ImageTelemetryGuard {
    public fun onImportStarted()

    public fun onImportSucceeded(event: ImageImportSucceededEvent)

    public fun onImportFailed(event: ImageImportFailedEvent)

    public object NoOp : ImageTelemetryGuard {
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
    public val outcome: ImageRejectedKind,
    public val durationMillis: Long,
)
