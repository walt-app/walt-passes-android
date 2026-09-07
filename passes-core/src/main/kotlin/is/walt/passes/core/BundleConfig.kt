package `is`.walt.passes.core

/**
 * Caps applied by [PassBundleReader] at the OUTER layer of a bundle, before any inner
 * archive reaches [PassParser]. Defaults are conservative for the kernel; a consumer with
 * a larger legitimate wallet (documents alongside passes) raises them deliberately.
 *
 * [maxEntryBytes] defaults to [ParserConfig.DEFAULT_MAX_ARCHIVE_BYTES]: an inner pass the
 * parser would refuse anyway is not worth inflating, so a consumer that raises
 * [ParserConfig.maxArchiveBytes] must raise this too. [maxCumulativeBytes] bounds the sum
 * of every entry's decompressed bytes, accepted or skipped, which no single-archive limit
 * does. [maxArchiveBytes] caps the outer file's compressed bytes and defaults to the
 * cumulative cap.
 */
public data class BundleConfig(
    public val maxArchiveBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
    public val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    public val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
    public val maxCumulativeBytes: Long = DEFAULT_MAX_CUMULATIVE_BYTES,
    public val allowlist: BundleEntryAllowlist = BundleEntryAllowlist.PkpassOnly,
) {
    public companion object {
        public const val DEFAULT_MAX_CUMULATIVE_BYTES: Long = 256L * 1024 * 1024
        public const val DEFAULT_MAX_ARCHIVE_BYTES: Long = DEFAULT_MAX_CUMULATIVE_BYTES
        public const val DEFAULT_MAX_ENTRIES: Int = 200
        public const val DEFAULT_MAX_ENTRY_BYTES: Long = ParserConfig.DEFAULT_MAX_ARCHIVE_BYTES
    }
}

/**
 * Decides which outer entries are handed to the sink as [BundleEntry.Accepted]. Declined
 * entries are still counted against every cap and reported as [BundleEntry.Skipped]. The
 * allowlist sees the entry name only, never bytes: type is for the consumer's byte sniff
 * and the kernel parsers to decide, not the extension.
 */
public fun interface BundleEntryAllowlist {
    public fun accepts(name: String): Boolean

    public companion object {
        /**
         * The kernel default and Apple's `.pkpasses` shape: root-level `<stem>.pkpass` only,
         * case-insensitive, with a non-empty stem. A nested `passes/1.pkpass` is a consumer
         * layout and needs a consumer allowlist.
         */
        public val PkpassOnly: BundleEntryAllowlist =
            BundleEntryAllowlist { name ->
                val suffix = ".${PassBundleReader.PASS_ENTRY_EXTENSION}"
                !name.contains('/') && name.length > suffix.length && name.endsWith(suffix, ignoreCase = true)
            }
    }
}

/** Which [BundleConfig] guard tripped. Enum so telemetry can name the cap without a string. */
public enum class BundleLimit {
    ArchiveSize,
    EntryCount,
    EntrySize,
    CumulativeSize,
}

/**
 * The configured ceiling for this limit (bytes for sizes, count for entries). The exhaustive
 * `when` is the drift detector: a [BundleLimit] arm without a [BundleConfig] field is a
 * compile error here.
 */
public fun BundleLimit.limitFrom(config: BundleConfig): Long =
    when (this) {
        BundleLimit.ArchiveSize -> config.maxArchiveBytes
        BundleLimit.EntryCount -> config.maxEntries.toLong()
        BundleLimit.EntrySize -> config.maxEntryBytes
        BundleLimit.CumulativeSize -> config.maxCumulativeBytes
    }
