package `is`.walt.passes.core

/**
 * Defensive limits and trust toggles applied during parsing. Defaults are tuned for the v1
 * consumer (Walt Android), erring on the side of generosity for legitimate passes from large
 * carriers (which can carry sizeable backgrounds and many locales) while still cutting off
 * obvious zip-bomb / JSON-bomb / decompression-bomb shapes far below process-OOM territory.
 *
 * Limits are enforced *before* full materialization, e.g. [maxArchiveBytes] is checked
 * against the input stream length before unzipping; [maxEntries] is checked while iterating
 * the central directory; [maxJsonDepth] is enforced inside the JSON reader.
 *
 * Use [Strict] for tests and audit tooling that should reject anything not Apple-signed.
 */
public data class ParserConfig(
    public val maxArchiveBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
    public val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    public val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
    public val maxJsonDepth: Int = DEFAULT_MAX_JSON_DEPTH,
    public val maxJsonStringBytes: Int = DEFAULT_MAX_JSON_STRING_BYTES,
    public val maxImagePixelCount: Int = DEFAULT_MAX_IMAGE_PIXEL_COUNT,
    public val maxLocaleCount: Int = DEFAULT_MAX_LOCALE_COUNT,
    public val acceptUnsignedArchives: Boolean = true,
    public val acceptSelfSignedCertificates: Boolean = true,
    public val telemetryGuard: TelemetryGuard = TelemetryGuard.NoOp,
) {
    public companion object {
        public const val DEFAULT_MAX_ARCHIVE_BYTES: Long = 10L * 1024 * 1024
        public const val DEFAULT_MAX_ENTRIES: Int = 256
        public const val DEFAULT_MAX_ENTRY_BYTES: Long = 4L * 1024 * 1024
        public const val DEFAULT_MAX_JSON_DEPTH: Int = 16
        public const val DEFAULT_MAX_JSON_STRING_BYTES: Int = 1 * 1024 * 1024
        public const val DEFAULT_MAX_IMAGE_PIXEL_COUNT: Int = 4096 * 4096
        public const val DEFAULT_MAX_LOCALE_COUNT: Int = 64

        /**
         * A configuration that rejects unsigned and self-signed archives. Provided for tests
         * and for an opt-in stricter ingestion mode; not the default per
         * decision-wlt-0tn-q1.
         */
        public val Strict: ParserConfig = ParserConfig(
            acceptUnsignedArchives = false,
            acceptSelfSignedCertificates = false,
        )
    }
}
