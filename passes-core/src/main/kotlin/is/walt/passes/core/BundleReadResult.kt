package `is`.walt.passes.core

/**
 * Outcome of one [PassBundleReader.read]. Every arm carries how many entries reached the
 * sink before it ended: [accepted] were handed over with bytes, [skipped] were reported
 * without. The result holds no entry name and no bytes, so it is safe to forward to
 * telemetry as-is; names travel only through [BundleEntry] to the sink.
 */
public sealed interface BundleReadResult {
    public val accepted: Int
    public val skipped: Int

    /** Every file entry was read and delivered. */
    public data class Completed(
        override val accepted: Int,
        override val skipped: Int,
    ) : BundleReadResult

    /** The sink returned [BundleVisit.Stop]; entries after that point were not read. */
    public data class Stopped(
        override val accepted: Int,
        override val skipped: Int,
    ) : BundleReadResult

    /**
     * The bundle was refused at some entry. Entries delivered before it stay delivered;
     * nothing after it was read. A cap hit inside an entry means that entry was never
     * handed over, even if it would have been accepted.
     */
    public data class Rejected(
        public val reason: BundleRejection,
        override val accepted: Int,
        override val skipped: Int,
    ) : BundleReadResult
}

public sealed interface BundleRejection {
    /** The bytes are not a ZIP (bad magic, truncated, or corrupt local headers). */
    public data object NotAZipArchive : BundleRejection

    /** An entry name is a zip-slip shape: `..`/`.` segments, absolute, backslash, drive letter, or empty. */
    public data object UnsafeEntryName : BundleRejection

    /** Two file entries share a name. The second could shadow the first, so the bundle is refused. */
    public data object DuplicateEntryName : BundleRejection

    public data class LimitExceeded(public val limit: BundleLimit) : BundleRejection
}

/**
 * Telemetry-safe flattening of [BundleRejection]: one enum value per sealed arm and per
 * [BundleLimit], so a consumer can name the guard that tripped without a string.
 */
public enum class BundleFailureReason {
    NotAZipArchive,
    UnsafeEntryName,
    DuplicateEntryName,
    ArchiveSizeLimit,
    EntryCountLimit,
    EntrySizeLimit,
    CumulativeSizeLimit,
}

/** Exhaustive by construction: a new [BundleRejection] or [BundleLimit] arm fails to compile here. */
public fun BundleRejection.toFailureReason(): BundleFailureReason =
    when (this) {
        BundleRejection.NotAZipArchive -> BundleFailureReason.NotAZipArchive
        BundleRejection.UnsafeEntryName -> BundleFailureReason.UnsafeEntryName
        BundleRejection.DuplicateEntryName -> BundleFailureReason.DuplicateEntryName
        is BundleRejection.LimitExceeded ->
            when (limit) {
                BundleLimit.ArchiveSize -> BundleFailureReason.ArchiveSizeLimit
                BundleLimit.EntryCount -> BundleFailureReason.EntryCountLimit
                BundleLimit.EntrySize -> BundleFailureReason.EntrySizeLimit
                BundleLimit.CumulativeSize -> BundleFailureReason.CumulativeSizeLimit
            }
    }
