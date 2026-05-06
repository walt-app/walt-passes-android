package `is`.walt.passes.pdf

/**
 * Hook for emitting PDF-import observability events to a host telemetry pipeline. The
 * shape of the event types is the load-bearing security control here: every parameter is
 * either an enum, a count, or a duration. There is no `String` carrying filename, no
 * `ByteArray` carrying file contents, no map of free-form attributes.
 *
 * That structural restriction is the trust claim mirroring `passes-core`'s
 * [is.walt.passes.core.TelemetryGuard]: PDF content and identifying metadata never appear
 * in logs or telemetry, by interface construction. A consumer cannot accidentally log a
 * filename through this interface because the interface refuses to accept one. Reviewers
 * should treat any future addition of a `String` / `CharSequence` / `ByteArray` / `Map`
 * parameter to these events as a security-policy change requiring re-review.
 *
 * The events are also intentionally narrower than the parser equivalents: there is no
 * "page-by-page render started" event, because per-page render timing leaks structural
 * information about a document the consumer would be tempted to act on.
 */
public interface DocumentTelemetryGuard {
    public fun onImportStarted()

    public fun onImportSucceeded(event: DocumentImportSucceededEvent)

    public fun onImportFailed(event: DocumentImportFailedEvent)

    public object NoOp : DocumentTelemetryGuard {
        override fun onImportStarted(): Unit = Unit

        override fun onImportSucceeded(event: DocumentImportSucceededEvent): Unit = Unit

        override fun onImportFailed(event: DocumentImportFailedEvent): Unit = Unit
    }
}

public data class DocumentImportSucceededEvent(
    public val byteCount: Long,
    public val pageCount: Int,
    public val durationMillis: Long,
)

public data class DocumentImportFailedEvent(
    public val outcome: DocumentRejectedKind,
    public val durationMillis: Long,
)
