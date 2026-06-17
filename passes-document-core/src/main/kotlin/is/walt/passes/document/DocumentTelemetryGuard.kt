package `is`.walt.passes.document

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

    /**
     * A consumer-side render attempt produced no bitmap. Distinct from the renderer
     * service's own failure path: this fires inside the hosting UI module after
     * `RenderResult.Ok` has been received, when reconstructing the bitmap from the
     * returned `SharedMemory` throws — out of memory, dimension mismatch, or a handle
     * already closed by a parallel render. The visible outcome is a blank page that
     * the next swipe re-attempts; without this hook the path is silent.
     *
     * The PII discipline is upheld by parameter shape: only the enum [reason] is
     * accepted. No `Throwable`, no message, no dimensions — see
     * `DocumentTelemetryGuardSurfaceTest` for the structural lock.
     */
    public fun onConsumerRenderFailed(reason: ConsumerRenderFailure)

    public object NoOp : DocumentTelemetryGuard {
        override fun onImportStarted(): Unit = Unit

        override fun onImportSucceeded(event: DocumentImportSucceededEvent): Unit = Unit

        override fun onImportFailed(event: DocumentImportFailedEvent): Unit = Unit

        override fun onConsumerRenderFailed(reason: ConsumerRenderFailure): Unit = Unit
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

/**
 * Why a consumer-side bitmap reconstruction failed. Mirrors the three deterministic
 * Android-side failure shapes plus a defensive [Other] catch-all:
 *
 *  - [OutOfMemory] — `Bitmap.createBitmap` or `copyPixelsFromBuffer` threw `OutOfMemoryError`.
 *  - [SharedMemoryUnavailable] — `SharedMemory.mapReadOnly()` threw `IllegalStateException`,
 *    the JDK-typed surface for "the handle was already closed", typically by a parallel
 *    render coroutine that cancelled and ran the cleanup branch.
 *  - [DimensionMismatch] — `copyPixelsFromBuffer` threw `BufferUnderflowException`, meaning
 *    the renderer-reported `widthPx * heightPx * 4` bytes did not match the mapped buffer.
 *  - [Other] — any other `Throwable`. Preserved to keep the outer `runCatching` surface
 *    safe against future Android changes; spike here means a new failure class to triage.
 */
public enum class ConsumerRenderFailure {
    OutOfMemory,
    SharedMemoryUnavailable,
    DimensionMismatch,
    Other,
}
