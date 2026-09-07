package `is`.walt.passes.core.internal

import `is`.walt.passes.core.BundleConfig
import `is`.walt.passes.core.BundleEntry
import `is`.walt.passes.core.BundleEntrySink
import `is`.walt.passes.core.BundleLimit
import `is`.walt.passes.core.BundleReadResult
import `is`.walt.passes.core.BundleRejection
import `is`.walt.passes.core.BundleVisit
import `is`.walt.passes.core.PassBundleReader
import `is`.walt.passes.core.PassSource
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * The single [PassBundleReader] implementation. Shares the extractor's stream chain (see
 * [extractSafely]: declared-size pre-check, magic sniff, [BoundedInputStream] outside the
 * buffer, [NonClosingInputStream] for caller-owned streams) and adds the cumulative cap.
 * Stateless beyond the immutable [config]; each [read] owns one [BundleWalk].
 */
internal class DefaultPassBundleReader(private val config: BundleConfig) : PassBundleReader {
    override fun read(
        source: PassSource,
        sink: BundleEntrySink,
    ): BundleReadResult {
        val declared = source.declaredSizeBytes()
        if (declared != null && declared > config.maxArchiveBytes) {
            return BundleReadResult.Rejected(BundleRejection.LimitExceeded(BundleLimit.ArchiveSize), 0, 0)
        }
        // Same wrapper order as extractSafely: the bound must sit outside the buffer so the
        // sniffed bytes still count when ZipInputStream reads them back.
        val sniffer = BufferedInputStream(source.openStream())
        return if (!hasZipMagic(sniffer)) {
            BundleReadResult.Rejected(BundleRejection.NotAZipArchive, 0, 0)
        } else {
            val bounded = BoundedInputStream(sniffer, config.maxArchiveBytes)
            ZipInputStream(bounded).use { zis -> BundleWalk(config, sink).run(zis) }
        }
    }
}

/** Outcome of advancing the archive to its next file entry. */
private sealed interface Pull {
    data object End : Pull

    data class File(val name: String, val ordinal: Int) : Pull

    data class Rejected(val reason: BundleRejection) : Pull
}

/**
 * Per-read mutable state: what has been seen, counted, and delivered. Only ZIP reads run
 * inside [guarded]; the allowlist and the sink are called outside it, so their exceptions
 * propagate instead of being folded into [BundleRejection.NotAZipArchive].
 */
private class BundleWalk(
    private val config: BundleConfig,
    private val sink: BundleEntrySink,
) {
    private val seenNames = HashSet<String>()
    private val budget = InflateBudget(config.maxCumulativeBytes)
    private var fileEntries = 0
    private var accepted = 0
    private var skipped = 0

    fun run(zis: ZipInputStream): BundleReadResult {
        while (true) {
            val outcome =
                when (val pull = guarded(Pull::Rejected) { pullNext(zis) }) {
                    Pull.End -> BundleReadResult.Completed(accepted, skipped)
                    is Pull.Rejected -> rejected(pull.reason)
                    is Pull.File -> readAndDeliver(zis, pull)
                }
            if (outcome != null) return outcome
        }
    }

    /** Advances to the next file entry, validating every name and draining directory payloads. */
    private fun pullNext(zis: ZipInputStream): Pull {
        while (true) {
            val entry = zis.nextEntry ?: return Pull.End
            val pull =
                when {
                    isUnsafeEntryName(entry.name) -> Pull.Rejected(BundleRejection.UnsafeEntryName)
                    entry.isDirectory -> inflate(zis, null)?.let { Pull.Rejected(it) }
                    else -> registerFile(entry.name)
                }
            if (pull != null) return pull
        }
    }

    private fun registerFile(name: String): Pull {
        val ordinal = fileEntries
        return when {
            !seenNames.add(name) -> Pull.Rejected(BundleRejection.DuplicateEntryName)
            ordinal >= config.maxEntries -> Pull.Rejected(BundleRejection.LimitExceeded(BundleLimit.EntryCount))
            else -> {
                fileEntries += 1
                Pull.File(name, ordinal)
            }
        }
    }

    private fun readAndDeliver(
        zis: ZipInputStream,
        file: Pull.File,
    ): BundleReadResult? {
        val buffer = if (config.allowlist.accepts(file.name)) ByteArrayOutputStream() else null
        val rejection = guarded({ it }) { inflate(zis, buffer) }
        return if (rejection != null) {
            rejected(rejection)
        } else {
            deliver(toEntry(file, buffer))
        }
    }

    private fun inflate(
        zis: ZipInputStream,
        buffer: ByteArrayOutputStream?,
    ): BundleRejection? =
        when (inflateBounded(zis, buffer, config.maxEntryBytes, budget)) {
            null -> null
            InflateLimit.EntrySize -> BundleRejection.LimitExceeded(BundleLimit.EntrySize)
            InflateLimit.CumulativeSize -> BundleRejection.LimitExceeded(BundleLimit.CumulativeSize)
        }

    private fun toEntry(
        file: Pull.File,
        buffer: ByteArrayOutputStream?,
    ): BundleEntry =
        if (buffer != null) {
            BundleEntry.Accepted(file.name, file.ordinal, buffer.toByteArray())
        } else {
            BundleEntry.Skipped(file.name, file.ordinal)
        }

    private fun deliver(entry: BundleEntry): BundleReadResult? {
        when (entry) {
            is BundleEntry.Accepted -> accepted += 1
            is BundleEntry.Skipped -> skipped += 1
        }
        return if (sink.onEntry(entry) == BundleVisit.Stop) BundleReadResult.Stopped(accepted, skipped) else null
    }

    private fun rejected(reason: BundleRejection): BundleReadResult {
        return BundleReadResult.Rejected(reason, accepted, skipped)
    }
}

/** Runs one ZIP read, mapping input-driven exceptions onto a rejection via [onRejected]. */
private inline fun <T> guarded(
    onRejected: (BundleRejection) -> T,
    block: () -> T,
): T =
    try {
        block()
    } catch (_: ArchiveSizeExceededException) {
        onRejected(BundleRejection.LimitExceeded(BundleLimit.ArchiveSize))
    } catch (_: IOException) {
        onRejected(BundleRejection.NotAZipArchive)
    } catch (_: IllegalArgumentException) {
        // ZipInputStream decodes EFS-flagged names as strict UTF-8 and throws this on a bad byte.
        onRejected(BundleRejection.NotAZipArchive)
    }
