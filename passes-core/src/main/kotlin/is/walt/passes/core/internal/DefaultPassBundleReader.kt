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

/** One outcome of advancing the walk by a single file entry. */
private sealed interface Step {
    data object End : Step

    data class Deliver(val entry: BundleEntry) : Step

    data class Rejected(val reason: BundleRejection) : Step
}

/**
 * Per-read mutable state: what has been seen, counted, and delivered. Input-driven
 * exceptions are caught per step so that a sink exception, raised outside [safeStep],
 * propagates to the caller instead of being folded into [BundleRejection.NotAZipArchive].
 */
private class BundleWalk(
    private val config: BundleConfig,
    private val sink: BundleEntrySink,
) {
    private val seenNames = HashSet<String>()
    private var fileEntries = 0
    private var accepted = 0
    private var skipped = 0
    private var cumulativeBytes = 0L

    fun run(zis: ZipInputStream): BundleReadResult {
        var outcome: BundleReadResult? = null
        while (outcome == null) {
            outcome =
                when (val step = safeStep(zis)) {
                    Step.End -> BundleReadResult.Completed(accepted, skipped)
                    is Step.Rejected -> BundleReadResult.Rejected(step.reason, accepted, skipped)
                    is Step.Deliver -> deliver(step.entry)
                }
        }
        return outcome
    }

    private fun deliver(entry: BundleEntry): BundleReadResult? =
        if (sink.onEntry(entry) == BundleVisit.Stop) BundleReadResult.Stopped(accepted, skipped) else null

    private fun safeStep(zis: ZipInputStream): Step =
        try {
            nextStep(zis)
        } catch (_: ArchiveSizeExceededException) {
            Step.Rejected(BundleRejection.LimitExceeded(BundleLimit.ArchiveSize))
        } catch (_: IOException) {
            Step.Rejected(BundleRejection.NotAZipArchive)
        } catch (_: IllegalArgumentException) {
            // ZipInputStream decodes EFS-flagged names as strict UTF-8 and throws this on a bad byte.
            Step.Rejected(BundleRejection.NotAZipArchive)
        }

    /** Advances past directory entries to the next file entry, validating every name on the way. */
    private fun nextStep(zis: ZipInputStream): Step {
        var step: Step? = null
        while (step == null) {
            val entry = zis.nextEntry ?: return Step.End
            step =
                when {
                    isUnsafeEntryName(entry.name) -> Step.Rejected(BundleRejection.UnsafeEntryName)
                    entry.isDirectory -> {
                        zis.closeEntry()
                        null
                    }
                    else -> fileEntryStep(zis, entry.name)
                }
        }
        return step
    }

    private fun fileEntryStep(
        zis: ZipInputStream,
        name: String,
    ): Step {
        val ordinal = fileEntries
        return when {
            !seenNames.add(name) -> Step.Rejected(BundleRejection.DuplicateEntryName)
            ordinal >= config.maxEntries -> Step.Rejected(BundleRejection.LimitExceeded(BundleLimit.EntryCount))
            else -> {
                fileEntries += 1
                readFileEntry(zis, name, ordinal)
            }
        }
    }

    private fun readFileEntry(
        zis: ZipInputStream,
        name: String,
        ordinal: Int,
    ): Step {
        val buffer = if (config.allowlist.accepts(name)) ByteArrayOutputStream() else null
        val tripped = inflateBounded(zis, buffer)
        return when {
            tripped != null -> Step.Rejected(BundleRejection.LimitExceeded(tripped))
            buffer != null -> {
                accepted += 1
                Step.Deliver(BundleEntry.Accepted(name, ordinal, buffer.toByteArray()))
            }
            else -> {
                skipped += 1
                Step.Deliver(BundleEntry.Skipped(name, ordinal))
            }
        }
    }

    /**
     * Inflates the current entry chunk by chunk, checking the per-entry cap and then the
     * cumulative cap after every read so a bomb is stopped mid-inflate. A null [buffer]
     * drains without retaining: skipped entries still pay into both caps.
     */
    private fun inflateBounded(
        zis: ZipInputStream,
        buffer: ByteArrayOutputStream?,
    ): BundleLimit? {
        val chunk = ByteArray(READ_BUFFER_SIZE)
        var entryBytes = 0L
        var tripped: BundleLimit? = null
        var n = zis.read(chunk)
        while (n != -1 && tripped == null) {
            entryBytes += n
            cumulativeBytes += n
            tripped =
                when {
                    entryBytes > config.maxEntryBytes -> BundleLimit.EntrySize
                    cumulativeBytes > config.maxCumulativeBytes -> BundleLimit.CumulativeSize
                    else -> null
                }
            if (tripped == null) {
                buffer?.write(chunk, 0, n)
                n = zis.read(chunk)
            }
        }
        return tripped
    }
}
