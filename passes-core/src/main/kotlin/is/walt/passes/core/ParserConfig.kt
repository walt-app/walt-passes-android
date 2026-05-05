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
 * [maxJsonStringBytes] is intentionally cross-format: it bounds individual string
 * values in `pass.json` *and* individual values in `<locale>.lproj/pass.strings`. The
 * two formats share an attack surface (a single oversized string deferring allocation
 * to a downstream consumer), so a single ceiling is the right knob; introducing a
 * separate `maxStringsValueBytes` would be a knob without a turner. Tighten this value
 * and both parsers tighten with it.
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
        public val Strict: ParserConfig =
            ParserConfig(
                acceptUnsignedArchives = false,
                acceptSelfSignedCertificates = false,
            )
    }
}

/**
 * The configured ceiling for this resource limit, expressed in the unit the parser actually
 * compares against (bytes for size limits, count for everything else). Returned as `Long`
 * so the caller can compare without overflow concerns on archive sizes.
 *
 * The exhaustive `when` is the drift detector: adding a [ResourceLimit] arm without giving
 * it a [ParserConfig] field is a compile error here, so an enum value can never silently
 * lack a backing limit.
 */
public fun ResourceLimit.limitFrom(config: ParserConfig): Long =
    when (this) {
        ResourceLimit.ArchiveSize -> config.maxArchiveBytes
        ResourceLimit.EntryCount -> config.maxEntries.toLong()
        ResourceLimit.EntrySize -> config.maxEntryBytes
        ResourceLimit.JsonDepth -> config.maxJsonDepth.toLong()
        ResourceLimit.JsonStringSize -> config.maxJsonStringBytes.toLong()
        ResourceLimit.ImagePixelCount -> config.maxImagePixelCount.toLong()
        ResourceLimit.LocaleCount -> config.maxLocaleCount.toLong()
    }
