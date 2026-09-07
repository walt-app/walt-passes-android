package `is`.walt.passes.core

import `is`.walt.passes.core.internal.DefaultPassBundleReader
import `is`.walt.passes.core.internal.sniffFirstLocalHeaderName

/**
 * Safe reader for the OUTER layer of a bundle of archives: an Apple `.pkpasses` (a flat ZIP
 * of `.pkpass` files) or a consumer superset of it that also carries documents and a
 * sidecar. A bundle is a ZIP of ZIPs, and [ParserConfig] / the hardened extractor bound
 * only ONE archive; a thousand inner entries each just under the per-archive cap is
 * gigabytes. This reader bounds the sum before any inner archive is opened.
 *
 * What it enforces, per [BundleConfig]: outer compressed size (declared-size pre-check plus
 * a streaming bound, since a size hint can lie), file-entry count, per-entry decompressed
 * size (stopped WHILE inflating, before the buffer materializes), cumulative decompressed
 * size across every file entry (accepted and skipped alike), zip-slip names, and duplicate
 * entry names. A caller-supplied [BundleEntryAllowlist] decides which entries are handed
 * over; everything else is reported as [BundleEntry.Skipped] so a consumer can count it,
 * never silently dropped. Directory entries are skipped silently and take no ordinal.
 *
 * What it deliberately does NOT do: open, parse, or validate an inner `.pkpass`. Each
 * accepted entry is delivered as bytes and the caller runs [PassParser] on it, so the
 * per-archive limits and signature validation apply unchanged and the kernel stays
 * agnostic to where the bundle came from. Symlinks: `java.util.zip` never exposes the
 * central-directory attributes Info-ZIP uses to mark one, and nothing here touches the
 * file system, so a symlink-shaped entry is just bytes (its link-target text).
 *
 * **Delivery shape.** Entries are delivered one at a time through [BundleEntrySink], each
 * as a bounded [ByteArray]. Heap is bounded by [BundleConfig.maxEntryBytes], not by the
 * cumulative cap: a materialized `List<BundleEntry>` would let a bundle at the cumulative
 * cap occupy hundreds of megabytes of phone heap at once, and an `InputStream` valid only
 * during the callback would push the typed per-entry cap into the inner parser's untyped
 * I/O failure path. The sink may return [BundleVisit.Stop] to abandon the rest.
 *
 * **Mid-stream rejection contract.** A rejection stops delivery at the offending entry and
 * surfaces as [BundleReadResult.Rejected]. Entries the sink already received stay
 * received: the reader cannot un-deliver, and the counts on the result say how many were.
 * A consumer that needs all-or-nothing must stage on its side.
 *
 * Never throws for input-driven failures; every such path is a [BundleReadResult] arm.
 * Exceptions thrown by the sink propagate to the caller unchanged.
 */
public fun interface PassBundleReader {
    /**
     * Streams [source] once and delivers each file entry to [sink] in archive order.
     * Synchronous and CPU-bound on the calling thread. Does not close a
     * [PassSource.Stream]; the caller owns its lifecycle.
     */
    public fun read(
        source: PassSource,
        sink: BundleEntrySink,
    ): BundleReadResult

    public companion object {
        /** Apple's registered MIME type for a `.pkpasses` bundle. */
        public const val MIME_TYPE: String = "application/vnd.apple.pkpasses"

        /** File extension of an Apple bundle, without the dot. */
        public const val FILE_EXTENSION: String = "pkpasses"

        /** Extension of the inner pass entries, without the dot. */
        public const val PASS_ENTRY_EXTENSION: String = "pkpass"

        /**
         * Construct a reader bound to [config]. Safe to call concurrently: each [read]
         * holds only stack-local state plus the immutable [config].
         */
        public fun create(config: BundleConfig = BundleConfig()): PassBundleReader = DefaultPassBundleReader(config)
    }
}

/** Receives each file entry as it is read. Return [BundleVisit.Stop] to abandon the rest. */
public fun interface BundleEntrySink {
    public fun onEntry(entry: BundleEntry): BundleVisit
}

public enum class BundleVisit {
    Continue,
    Stop,
}

/**
 * One file entry of the outer ZIP. [ordinal] is the 0-based position among file entries
 * (accepted and skipped alike; directory entries take no ordinal), so a consumer can bind
 * `(ordinal, name)` across two reads of the same file.
 */
public sealed interface BundleEntry {
    public val name: String
    public val ordinal: Int

    /**
     * An allowlisted entry with its fully inflated bytes, bounded by
     * [BundleConfig.maxEntryBytes]. The array is a fresh copy the receiver owns.
     */
    public class Accepted(
        override val name: String,
        override val ordinal: Int,
        public val bytes: ByteArray,
    ) : BundleEntry

    /**
     * An entry the allowlist declined. Its bytes were inflated only to count them against
     * the caps and were never handed out. Reported, not dropped, so a consumer can say
     * "N entries ignored".
     */
    public data class Skipped(
        override val name: String,
        override val ordinal: Int,
    ) : BundleEntry
}

/**
 * Outcome of [sniffPassBundle]. A `.pkpasses` and a `.pkpass` are both plain ZIPs, so the
 * 4-byte magic alone cannot tell them apart; the sniff reads the FIRST local file header's
 * name instead. It is a dispatch hint, never a trust decision.
 */
public enum class PassBundleSniff {
    /** The first entry name ends in `.pkpass`: only a bundle contains pass archives. */
    Bundle,

    /** The first entry is a file whose name does not end in `.pkpass`: a single pass or some other ZIP. */
    NotBundle,

    /**
     * Not decidable from [header]: not a ZIP local file header, an empty archive, a
     * directory entry first, or too few bytes to hold the first name. Run the reader to
     * find out; it handles leading junk by skipping it.
     */
    Undetermined,

    ;

    public companion object {
        /** Enough header to decide for any first-entry name up to 255 bytes. */
        public const val RECOMMENDED_HEADER_BYTES: Int = 30 + 255
    }
}

/**
 * Cheap dispatch helper over the first bytes of a file. Pass at least
 * [PassBundleSniff.RECOMMENDED_HEADER_BYTES]; fewer bytes make [PassBundleSniff.Undetermined]
 * more likely, never a wrong answer. A leading junk entry (`__MACOSX/`, `.DS_Store`) can
 * make a real bundle read as [PassBundleSniff.NotBundle]; consumers that must be sure
 * fall back to a full read.
 */
public fun sniffPassBundle(header: ByteArray): PassBundleSniff {
    val name = sniffFirstLocalHeaderName(header)
    return when {
        name == null || name.endsWith('/') -> PassBundleSniff.Undetermined
        name.endsWith(".${PassBundleReader.PASS_ENTRY_EXTENSION}", ignoreCase = true) -> PassBundleSniff.Bundle
        else -> PassBundleSniff.NotBundle
    }
}
