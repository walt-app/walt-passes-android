package `is`.walt.passes.pdf

/**
 * Defensive limits and the telemetry hook applied during PDF import. Defaults pin the
 * hard caps from ADR 0005 D7: 25 MB total, 10 pages, 5 s render timeout. They are exposed
 * as constants so consumers and tests refer to the same numbers, and so changing a default
 * is a deliberate, test-breaking edit (see [PublicApiSurfaceTest]).
 *
 * The limits are enforced before full materialization: [maxBytes] is checked against the
 * input source length before the renderer service even sees the bytes; [maxPages] is
 * checked after the page-count probe but before any rendering work; [renderTimeoutMs]
 * bounds each render call independently and is the watchdog the renderer service uses
 * before terminating itself (D7 timeout-then-kill behaviour).
 *
 * [telemetryGuard] follows the same load-bearing-by-shape contract as `passes-core`:
 * the events accept enums, counts, and durations only.
 */
public data class PdfImportConfig(
    public val maxBytes: Long = DEFAULT_MAX_BYTES,
    public val maxPages: Int = DEFAULT_MAX_PAGES,
    public val renderTimeoutMs: Long = DEFAULT_RENDER_TIMEOUT_MS,
    public val telemetryGuard: DocumentTelemetryGuard = DocumentTelemetryGuard.NoOp,
) {
    public companion object {
        public const val DEFAULT_MAX_BYTES: Long = 25L * 1024 * 1024
        public const val DEFAULT_MAX_PAGES: Int = 10
        public const val DEFAULT_RENDER_TIMEOUT_MS: Long = 5_000L
    }
}
