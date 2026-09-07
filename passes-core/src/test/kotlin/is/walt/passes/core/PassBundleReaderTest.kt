package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Behavior tests for the outer-layer bundle reader. Archives are synthesized in-memory
 * with [ZipOutputStream] so every hostile shape is readable source, not a binary fixture.
 * Inner `.pkpass` payloads are opaque bytes here: the reader never opens them.
 */
class PassBundleReaderTest {
    @Test
    fun happyPathDeliversPkpassEntriesInOrderWithBytes() {
        val zip =
            buildArchive {
                entry("a.pkpass", "AAA".toByteArray())
                entry("b.pkpass", "BB".toByteArray())
                entry("c.pkpass", "C".toByteArray())
            }
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), sink)

        assertThat(result).isEqualTo(BundleReadResult.Completed(accepted = 3, skipped = 0))
        assertThat(sink.names).containsExactly("a.pkpass", "b.pkpass", "c.pkpass").inOrder()
        assertThat(sink.ordinals).containsExactly(0, 1, 2).inOrder()
        assertThat(sink.acceptedBytes["b.pkpass"]).isEqualTo("BB".toByteArray())
    }

    @Test
    fun emptyBundleCompletesWithNoEntries() {
        val zip = buildArchive { }
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), sink)
        assertThat(result).isEqualTo(BundleReadResult.Completed(accepted = 0, skipped = 0))
        assertThat(sink.names).isEmpty()
    }

    @Test
    fun nonZipBytesAreRejectedBeforeAnyDelivery() {
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Bytes("not a zip".toByteArray()), sink)
        assertThat(result).isEqualTo(BundleReadResult.Rejected(BundleRejection.NotAZipArchive, 0, 0))
        assertThat(sink.names).isEmpty()
    }

    @Test
    fun emptyBytesAreRejectedAsNotAZipArchive() {
        val result = PassBundleReader.create().read(PassSource.Bytes(ByteArray(0)), RecordingSink())
        assertThat(result).isEqualTo(BundleReadResult.Rejected(BundleRejection.NotAZipArchive, 0, 0))
    }

    @Test
    fun directoryEntriesAreSkippedSilentlyAndDoNotOccupyAnOrdinal() {
        val zip =
            buildArchive {
                directory("passes/")
                entry("a.pkpass", "A".toByteArray())
                directory("documents/")
                entry("b.pkpass", "B".toByteArray())
            }
        val sink = RecordingSink()
        val result = PassBundleReader.create(BundleConfig(maxEntries = 2)).read(PassSource.Bytes(zip), sink)
        assertThat(result).isEqualTo(BundleReadResult.Completed(accepted = 2, skipped = 0))
        assertThat(sink.names).containsExactly("a.pkpass", "b.pkpass").inOrder()
        assertThat(sink.ordinals).containsExactly(0, 1).inOrder()
    }

    @Test
    fun nonPkpassEntryIsReportedSkippedUnderDefaultAllowlist() {
        val zip =
            buildArchive {
                entry("a.pkpass", "A".toByteArray())
                entry("walt-manifest.json", "{}".toByteArray())
                entry("b.pkpass", "B".toByteArray())
            }
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), sink)
        assertThat(result).isEqualTo(BundleReadResult.Completed(accepted = 2, skipped = 1))
        assertThat(sink.names).containsExactly("a.pkpass", "walt-manifest.json", "b.pkpass").inOrder()
        assertThat(sink.ordinals).containsExactly(0, 1, 2).inOrder()
        assertThat(sink.skippedNames).containsExactly("walt-manifest.json")
        assertThat(sink.acceptedBytes.keys).containsExactly("a.pkpass", "b.pkpass")
    }

    @Test
    fun callerAllowlistAcceptsEntriesTheKernelDefaultWouldSkip() {
        val zip =
            buildArchive {
                entry("passes/1.pkpass", "P".toByteArray())
                entry("documents/1.pdf", "%PDF".toByteArray())
                entry("walt-manifest.json", "{}".toByteArray())
                entry("junk.exe", "MZ".toByteArray())
            }
        val allowlist =
            BundleEntryAllowlist { name ->
                name.startsWith("passes/") || name.startsWith("documents/") || name == "walt-manifest.json"
            }
        val sink = RecordingSink()
        val result =
            PassBundleReader.create(BundleConfig(allowlist = allowlist)).read(PassSource.Bytes(zip), sink)
        assertThat(result).isEqualTo(BundleReadResult.Completed(accepted = 3, skipped = 1))
        assertThat(sink.acceptedBytes.keys)
            .containsExactly("passes/1.pkpass", "documents/1.pdf", "walt-manifest.json")
        assertThat(sink.skippedNames).containsExactly("junk.exe")
    }

    @Test
    fun defaultAllowlistIsRootOnlyAndCaseInsensitive() {
        val zip =
            buildArchive {
                entry("passes/1.pkpass", "P".toByteArray())
                entry("UPPER.PKPASS", "U".toByteArray())
                entry("__MACOSX/._a.pkpass", "junk".toByteArray())
            }
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), sink)
        assertThat(result).isEqualTo(BundleReadResult.Completed(accepted = 1, skipped = 2))
        assertThat(sink.acceptedBytes.keys).containsExactly("UPPER.PKPASS")
        assertThat(sink.skippedNames).containsExactly("passes/1.pkpass", "__MACOSX/._a.pkpass")
    }

    @Test
    fun entryCountCapTripsOnTheEntryPastTheCapAndCountsSkippedEntries() {
        val zip =
            buildArchive {
                entry("a.pkpass", "A".toByteArray())
                entry("ignored.txt", "x".toByteArray())
                entry("b.pkpass", "B".toByteArray())
            }
        val sink = RecordingSink()
        val result = PassBundleReader.create(BundleConfig(maxEntries = 2)).read(PassSource.Bytes(zip), sink)
        assertThat(result)
            .isEqualTo(BundleReadResult.Rejected(BundleRejection.LimitExceeded(BundleLimit.EntryCount), 1, 1))
        assertThat(sink.names).containsExactly("a.pkpass", "ignored.txt").inOrder()
    }

    @Test
    fun perEntryCapStopsInflatingAZipBombBeforeItMaterializes() {
        // 1 MB of zeros compresses to about 1 KB. With a 4 KB per-entry cap the reader
        // must stop mid-inflate and never hand the sink a buffer.
        val zip = buildArchive { entry("bomb.pkpass", ByteArray(1_024 * 1_024)) }
        val sink = RecordingSink()
        val config = BundleConfig(maxEntryBytes = 4_096)
        val result = PassBundleReader.create(config).read(PassSource.Bytes(zip), sink)
        assertThat(result)
            .isEqualTo(BundleReadResult.Rejected(BundleRejection.LimitExceeded(BundleLimit.EntrySize), 0, 0))
        assertThat(sink.names).isEmpty()
    }

    @Test
    fun cumulativeCapTripsWhenEveryEntryIsIndividuallyUnderCap() {
        // Three 3 KB entries: each under the 4 KB per-entry cap, together over 8 KB.
        val zip =
            buildArchive {
                entry("a.pkpass", ByteArray(3_072))
                entry("b.pkpass", ByteArray(3_072))
                entry("c.pkpass", ByteArray(3_072))
            }
        val sink = RecordingSink()
        val config = BundleConfig(maxEntryBytes = 4_096, maxCumulativeBytes = 8_192)
        val result = PassBundleReader.create(config).read(PassSource.Bytes(zip), sink)
        assertThat(result)
            .isEqualTo(BundleReadResult.Rejected(BundleRejection.LimitExceeded(BundleLimit.CumulativeSize), 2, 0))
        assertThat(sink.names).containsExactly("a.pkpass", "b.pkpass").inOrder()
    }

    @Test
    fun skippedEntriesStillCountTowardTheCumulativeCap() {
        val zip =
            buildArchive {
                entry("big.bin", ByteArray(6_000))
                entry("a.pkpass", ByteArray(3_000))
            }
        val sink = RecordingSink()
        val config = BundleConfig(maxEntryBytes = 8_192, maxCumulativeBytes = 8_192)
        val result = PassBundleReader.create(config).read(PassSource.Bytes(zip), sink)
        assertThat(result)
            .isEqualTo(BundleReadResult.Rejected(BundleRejection.LimitExceeded(BundleLimit.CumulativeSize), 0, 1))
        assertThat(sink.skippedNames).containsExactly("big.bin")
    }

    @Test
    fun skippedEntriesStillHonourThePerEntryCap() {
        val zip = buildArchive { entry("bomb.bin", ByteArray(1_024 * 1_024)) }
        val sink = RecordingSink()
        val result = PassBundleReader.create(BundleConfig(maxEntryBytes = 4_096)).read(PassSource.Bytes(zip), sink)
        assertThat(result)
            .isEqualTo(BundleReadResult.Rejected(BundleRejection.LimitExceeded(BundleLimit.EntrySize), 0, 0))
    }

    @Test
    fun zipSlipNamesRejectTheWholeBundle() {
        val hostile =
            listOf("../escape.pkpass", "/abs.pkpass", "a\\b.pkpass", "C:\\x.pkpass", "a/./b.pkpass", "a//b.pkpass")
        for (name in hostile) {
            val zip = buildArchive { entry(name, "x".toByteArray()) }
            val sink = RecordingSink()
            val result = PassBundleReader.create().read(PassSource.Bytes(zip), sink)
            assertThat(result).isEqualTo(BundleReadResult.Rejected(BundleRejection.UnsafeEntryName, 0, 0))
            assertThat(sink.names).isEmpty()
        }
    }

    @Test
    fun zipSlipNameIsRejectedEvenWhenTheAllowlistWouldSkipIt() {
        val zip = buildArchive { entry("../not-allowlisted.txt", "x".toByteArray()) }
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), RecordingSink())
        assertThat(result).isEqualTo(BundleReadResult.Rejected(BundleRejection.UnsafeEntryName, 0, 0))
    }

    @Test
    fun zipSlipDirectoryEntryIsRejected() {
        val zip = buildArchive { directory("../up/") }
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), RecordingSink())
        assertThat(result).isEqualTo(BundleReadResult.Rejected(BundleRejection.UnsafeEntryName, 0, 0))
    }

    @Test
    fun duplicateEntryNamesRejectTheBundleAfterTheFirstCopyWasDelivered() {
        val zip = buildArchiveWithDuplicateEntry("a.pkpass", "first".toByteArray(), "second".toByteArray())
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), sink)
        assertThat(result).isEqualTo(BundleReadResult.Rejected(BundleRejection.DuplicateEntryName, 1, 0))
        assertThat(sink.acceptedBytes["a.pkpass"]).isEqualTo("first".toByteArray())
    }

    @Test
    fun symlinkShapedEntryIsDeliveredAsPlainBytesNeverResolved() {
        // java.util.zip exposes no symlink notion: Info-ZIP marks a symlink via the
        // central directory's external attributes, which ZipInputStream never reads.
        // The reader treats such an entry as ordinary bytes (the link target text).
        val target = "../../etc/passwd".toByteArray()
        val zip = markFirstEntryAsUnixSymlink(buildArchive { entry("link.pkpass", target) })
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), sink)
        assertThat(result).isEqualTo(BundleReadResult.Completed(accepted = 1, skipped = 0))
        assertThat(sink.acceptedBytes["link.pkpass"]).isEqualTo(target)
    }

    @Test
    fun declaredSizeOverOuterArchiveCapFailsFastWithoutReading() {
        val tracker = OpenTrackingInputStream(ByteArrayInputStream(ByteArray(0)))
        val config = BundleConfig(maxArchiveBytes = 1_024)
        val result =
            PassBundleReader.create(config)
                .read(PassSource.Stream(tracker, sizeHintBytes = 1_025), RecordingSink())
        assertThat(result)
            .isEqualTo(BundleReadResult.Rejected(BundleRejection.LimitExceeded(BundleLimit.ArchiveSize), 0, 0))
        assertThat(tracker.bytesRead).isEqualTo(0)
    }

    @Test
    fun outerArchiveCapIsEnforcedWhileStreamingWhenTheSizeHintLies() {
        val zip = buildArchive { entry("a.pkpass", ByteArray(4_096) { it.toByte() }) }
        val config = BundleConfig(maxArchiveBytes = 64)
        val result =
            PassBundleReader.create(config)
                .read(PassSource.Stream(ByteArrayInputStream(zip), sizeHintBytes = null), RecordingSink())
        assertThat(result)
            .isEqualTo(BundleReadResult.Rejected(BundleRejection.LimitExceeded(BundleLimit.ArchiveSize), 0, 0))
    }

    @Test
    fun bytesSourceOverOuterArchiveCapFailsFast() {
        val zip = buildArchive { entry("a.pkpass", ByteArray(4_096) { it.toByte() }) }
        val result =
            PassBundleReader.create(BundleConfig(maxArchiveBytes = 64)).read(PassSource.Bytes(zip), RecordingSink())
        assertThat(result)
            .isEqualTo(BundleReadResult.Rejected(BundleRejection.LimitExceeded(BundleLimit.ArchiveSize), 0, 0))
    }

    @Test
    fun rejectionMidStreamStopsDeliveryButKeepsEntriesAlreadyDelivered() {
        val zip =
            buildArchive {
                entry("a.pkpass", "A".toByteArray())
                entry("b.pkpass", "B".toByteArray())
                entry("../c.pkpass", "C".toByteArray())
                entry("d.pkpass", "D".toByteArray())
            }
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), sink)
        assertThat(result).isEqualTo(BundleReadResult.Rejected(BundleRejection.UnsafeEntryName, 2, 0))
        assertThat(sink.names).containsExactly("a.pkpass", "b.pkpass").inOrder()
    }

    @Test
    fun sinkStopHaltsDeliveryAndReportsStopped() {
        val zip =
            buildArchive {
                entry("a.pkpass", "A".toByteArray())
                entry("b.pkpass", "B".toByteArray())
            }
        val sink = RecordingSink(stopAfter = 1)
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), sink)
        assertThat(result).isEqualTo(BundleReadResult.Stopped(accepted = 1, skipped = 0))
        assertThat(sink.names).containsExactly("a.pkpass")
    }

    @Test
    fun sinkExceptionsPropagateToTheCaller() {
        val zip = buildArchive { entry("a.pkpass", "A".toByteArray()) }
        val boom = IllegalStateException("sink failed")
        val thrown =
            runCatching {
                PassBundleReader.create().read(PassSource.Bytes(zip)) { throw boom }
            }.exceptionOrNull()
        assertThat(thrown).isSameInstanceAs(boom)
    }

    @Test
    fun streamSourceIsLeftOpenOnCompletionAndOnRejection() {
        val good = buildArchive { entry("a.pkpass", "A".toByteArray()) }
        val goodTracker = OpenTrackingInputStream(ByteArrayInputStream(good))
        PassBundleReader.create().read(PassSource.Stream(goodTracker), RecordingSink())
        assertThat(goodTracker.closed).isFalse()

        val bad = buildArchive { entry("../a.pkpass", "A".toByteArray()) }
        val badTracker = OpenTrackingInputStream(ByteArrayInputStream(bad))
        PassBundleReader.create().read(PassSource.Stream(badTracker), RecordingSink())
        assertThat(badTracker.closed).isFalse()
    }

    @Test
    fun acceptedBytesAreACopyTheCallerOwns() {
        val zip = buildArchive { entry("a.pkpass", "AAA".toByteArray()) }
        val sink = RecordingSink()
        PassBundleReader.create().read(PassSource.Bytes(zip), sink)
        val delivered = sink.acceptedBytes.getValue("a.pkpass")
        delivered[0] = 0x5A
        // A second read of the same bytes is unaffected by the mutation above.
        val again = RecordingSink()
        PassBundleReader.create().read(PassSource.Bytes(zip), again)
        assertThat(again.acceptedBytes.getValue("a.pkpass")).isEqualTo("AAA".toByteArray())
    }

    @Test
    fun sniffRecognisesABundleFromTheFirstLocalHeader() {
        val bundle = buildArchive { entry("first.pkpass", "x".toByteArray()) }
        assertThat(sniffPassBundle(bundle.copyOf(PassBundleSniff.RECOMMENDED_HEADER_BYTES)))
            .isEqualTo(PassBundleSniff.Bundle)
        assertThat(sniffPassBundle(buildArchive { entry("passes/1.pkpass", "x".toByteArray()) }))
            .isEqualTo(PassBundleSniff.Bundle)
        assertThat(sniffPassBundle(buildArchive { entry("MIXED.PkPass", "x".toByteArray()) }))
            .isEqualTo(PassBundleSniff.Bundle)
    }

    @Test
    fun sniffReportsASinglePassAsNotABundle() {
        val single = buildArchive { entry("pass.json", "{}".toByteArray()) }
        assertThat(sniffPassBundle(single)).isEqualTo(PassBundleSniff.NotBundle)
    }

    @Test
    fun sniffIsUndeterminedWhenTheHeaderCannotDecide() {
        val bundle = buildArchive { entry("first.pkpass", "x".toByteArray()) }
        assertThat(sniffPassBundle(bundle.copyOf(12))).isEqualTo(PassBundleSniff.Undetermined)
        assertThat(sniffPassBundle("not a zip at all, but long enough to hold a header".toByteArray()))
            .isEqualTo(PassBundleSniff.Undetermined)
        assertThat(sniffPassBundle(ByteArray(0))).isEqualTo(PassBundleSniff.Undetermined)
        assertThat(sniffPassBundle(buildArchive { })).isEqualTo(PassBundleSniff.Undetermined)
        assertThat(sniffPassBundle(buildArchive { directory("passes/") })).isEqualTo(PassBundleSniff.Undetermined)
    }

    @Test
    fun mimeAndExtensionConstantsAreApples() {
        assertThat(PassBundleReader.MIME_TYPE).isEqualTo("application/vnd.apple.pkpasses")
        assertThat(PassBundleReader.FILE_EXTENSION).isEqualTo("pkpasses")
        assertThat(PassBundleReader.PASS_ENTRY_EXTENSION).isEqualTo("pkpass")
    }

    @Test
    fun bundleConfigDefaultsAreConservative() {
        val cfg = BundleConfig()
        assertThat(cfg.maxEntries).isEqualTo(200)
        assertThat(cfg.maxEntryBytes).isEqualTo(ParserConfig.DEFAULT_MAX_ARCHIVE_BYTES)
        assertThat(cfg.maxCumulativeBytes).isEqualTo(256L * 1024 * 1024)
        assertThat(cfg.maxArchiveBytes).isEqualTo(256L * 1024 * 1024)
        assertThat(cfg.allowlist).isSameInstanceAs(BundleEntryAllowlist.PkpassOnly)
        for (limit in BundleLimit.entries) {
            assertThat(limit.limitFrom(cfg)).isGreaterThan(0L)
        }
    }

    @Test
    fun bundleRejectionFlattensToADistinctFailureReasonPerArm() {
        val all: List<BundleRejection> =
            listOf(
                BundleRejection.NotAZipArchive,
                BundleRejection.UnsafeEntryName,
                BundleRejection.DuplicateEntryName,
            ) + BundleLimit.entries.map { BundleRejection.LimitExceeded(it) }
        val mapped = all.map { it.toFailureReason() }
        assertThat(mapped).hasSize(all.size)
        assertThat(mapped.toSet()).containsExactlyElementsIn(BundleFailureReason.entries)
    }
}

/** Records every entry the reader hands over; optionally stops after [stopAfter] entries. */
private class RecordingSink(private val stopAfter: Int = Int.MAX_VALUE) : BundleEntrySink {
    val names = mutableListOf<String>()
    val ordinals = mutableListOf<Int>()
    val acceptedBytes = LinkedHashMap<String, ByteArray>()
    val skippedNames = mutableListOf<String>()

    override fun onEntry(entry: BundleEntry): BundleVisit {
        names += entry.name
        ordinals += entry.ordinal
        when (entry) {
            is BundleEntry.Accepted -> acceptedBytes[entry.name] = entry.bytes
            is BundleEntry.Skipped -> skippedNames += entry.name
        }
        return if (names.size >= stopAfter) BundleVisit.Stop else BundleVisit.Continue
    }
}

private fun buildArchive(block: ArchiveBuilder.() -> Unit): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos -> ArchiveBuilder(zos).block() }
    return baos.toByteArray()
}

private class ArchiveBuilder(private val zos: ZipOutputStream) {
    fun entry(
        name: String,
        content: ByteArray,
    ) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content)
        zos.closeEntry()
    }

    fun directory(name: String) {
        require(name.endsWith('/')) { "directory entries must end with '/'" }
        zos.putNextEntry(ZipEntry(name))
        zos.closeEntry()
    }
}

/**
 * Splices the local-file-header bodies of two single-entry archives ahead of the first
 * archive's central directory, since [ZipOutputStream] refuses duplicate names itself.
 */
private fun buildArchiveWithDuplicateEntry(
    name: String,
    first: ByteArray,
    second: ByteArray,
): ByteArray {
    val archiveA = buildArchive { entry(name, first) }
    val archiveB = buildArchive { entry(name, second) }
    val cdA = findCentralDirectoryOffset(archiveA)
    val cdB = findCentralDirectoryOffset(archiveB)
    val out = ByteArrayOutputStream()
    out.write(archiveA, 0, cdA)
    out.write(archiveB, 0, cdB)
    out.write(archiveA, cdA, archiveA.size - cdA)
    return out.toByteArray()
}

/**
 * Rewrites the first central-directory header the way Info-ZIP marks a Unix symlink:
 * "version made by" host = Unix (3) and external attributes = `0120777 << 16`.
 */
private fun markFirstEntryAsUnixSymlink(zip: ByteArray): ByteArray {
    val cd = findCentralDirectoryOffset(zip)
    val patched = zip.copyOf()
    patched[cd + 5] = 3
    val mode = 0xA1FF0000.toInt()
    for (i in 0 until 4) {
        patched[cd + 38 + i] = (mode ushr 8 * i).toByte()
    }
    return patched
}

private fun findCentralDirectoryOffset(bytes: ByteArray): Int {
    val sig = byteArrayOf(0x50, 0x4B, 0x01, 0x02)
    for (i in 0..bytes.size - sig.size) {
        if (sig.indices.all { k -> bytes[i + k] == sig[k] }) return i
    }
    error("no central directory found in synthetic archive")
}

private class OpenTrackingInputStream(private val delegate: InputStream) : InputStream() {
    var closed: Boolean = false
        private set
    var bytesRead: Long = 0
        private set

    override fun read(): Int {
        val b = delegate.read()
        if (b != -1) bytesRead += 1
        return b
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) bytesRead += n
        return n
    }

    override fun close() {
        closed = true
        delegate.close()
    }
}
