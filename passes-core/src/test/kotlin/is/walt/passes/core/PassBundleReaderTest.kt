package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.internal.OpenTrackingInputStream
import `is`.walt.passes.core.internal.ThrowingInputStream
import `is`.walt.passes.core.internal.buildArchive
import `is`.walt.passes.core.internal.buildArchiveWithDuplicateEntry
import `is`.walt.passes.core.internal.corruptFirstNameByte
import `is`.walt.passes.core.internal.findCentralDirectoryOffset
import `is`.walt.passes.core.internal.findNthLocalHeaderOffset
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Random

/**
 * Behavior tests for the outer-layer bundle reader. Archives come from the shared
 * in-memory fixtures in `ZipFixtures.kt`, so every hostile shape is readable source, not a
 * binary fixture. Inner `.pkpass` payloads are opaque bytes here: the reader never opens them.
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
        assertThat(result).isEqualTo(
            BundleReadResult.Rejected(
                BundleRejection.NotAZipArchive,
                accepted = 0,
                skipped = 0,
            ),
        )
        assertThat(sink.names).isEmpty()
    }

    @Test
    fun emptyBytesAreRejectedAsNotAZipArchive() {
        val result = PassBundleReader.create().read(PassSource.Bytes(ByteArray(0)), RecordingSink())
        assertThat(result).isEqualTo(
            BundleReadResult.Rejected(
                BundleRejection.NotAZipArchive,
                accepted = 0,
                skipped = 0,
            ),
        )
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
            .isEqualTo(
                BundleReadResult.Rejected(
                    BundleRejection.LimitExceeded(BundleLimit.EntryCount),
                    accepted = 1,
                    skipped = 1,
                ),
            )
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
            .isEqualTo(
                BundleReadResult.Rejected(
                    BundleRejection.LimitExceeded(BundleLimit.EntrySize),
                    accepted = 0,
                    skipped = 0,
                ),
            )
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
            .isEqualTo(
                BundleReadResult.Rejected(
                    BundleRejection.LimitExceeded(BundleLimit.CumulativeSize),
                    accepted = 2,
                    skipped = 0,
                ),
            )
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
            .isEqualTo(
                BundleReadResult.Rejected(
                    BundleRejection.LimitExceeded(BundleLimit.CumulativeSize),
                    accepted = 0,
                    skipped = 1,
                ),
            )
        assertThat(sink.skippedNames).containsExactly("big.bin")
    }

    @Test
    fun skippedEntriesStillHonourThePerEntryCap() {
        val zip = buildArchive { entry("bomb.bin", ByteArray(1_024 * 1_024)) }
        val sink = RecordingSink()
        val result = PassBundleReader.create(BundleConfig(maxEntryBytes = 4_096)).read(PassSource.Bytes(zip), sink)
        assertThat(result)
            .isEqualTo(
                BundleReadResult.Rejected(
                    BundleRejection.LimitExceeded(BundleLimit.EntrySize),
                    accepted = 0,
                    skipped = 0,
                ),
            )
    }

    @Test
    fun directoryEntryPayloadIsChargedAgainstThePerEntryCap() {
        // A name ending in "/" can still carry a deflate payload; it must not bypass the caps.
        val zip = buildArchive { entry("passes/", ByteArray(1_024 * 1_024)) }
        val sink = RecordingSink()
        val result = PassBundleReader.create(BundleConfig(maxEntryBytes = 4_096)).read(PassSource.Bytes(zip), sink)
        assertThat(result)
            .isEqualTo(
                BundleReadResult.Rejected(
                    BundleRejection.LimitExceeded(BundleLimit.EntrySize),
                    accepted = 0,
                    skipped = 0,
                ),
            )
        assertThat(sink.names).isEmpty()
    }

    @Test
    fun directoryEntryPayloadsAreChargedAgainstTheCumulativeCap() {
        val zip =
            buildArchive {
                entry("a/", ByteArray(3_000))
                entry("b/", ByteArray(3_000))
                entry("c/", ByteArray(3_000))
                entry("x.pkpass", "X".toByteArray())
            }
        val sink = RecordingSink()
        val config = BundleConfig(maxEntryBytes = 4_096, maxCumulativeBytes = 8_192)
        val result = PassBundleReader.create(config).read(PassSource.Bytes(zip), sink)
        assertThat(result)
            .isEqualTo(
                BundleReadResult.Rejected(
                    BundleRejection.LimitExceeded(BundleLimit.CumulativeSize),
                    accepted = 0,
                    skipped = 0,
                ),
            )
        assertThat(sink.names).isEmpty()
    }

    @Test
    fun zipSlipNamesRejectTheWholeBundle() {
        val hostile =
            listOf("../escape.pkpass", "/abs.pkpass", "a\\b.pkpass", "C:\\x.pkpass", "a/./b.pkpass", "a//b.pkpass")
        for (name in hostile) {
            val zip = buildArchive { entry(name, "x".toByteArray()) }
            val sink = RecordingSink()
            val result = PassBundleReader.create().read(PassSource.Bytes(zip), sink)
            assertThat(result).isEqualTo(
                BundleReadResult.Rejected(
                    BundleRejection.UnsafeEntryName,
                    accepted = 0,
                    skipped = 0,
                ),
            )
            assertThat(sink.names).isEmpty()
        }
    }

    @Test
    fun zipSlipNameIsRejectedEvenWhenTheAllowlistWouldSkipIt() {
        val zip = buildArchive { entry("../not-allowlisted.txt", "x".toByteArray()) }
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), RecordingSink())
        assertThat(result).isEqualTo(
            BundleReadResult.Rejected(
                BundleRejection.UnsafeEntryName,
                accepted = 0,
                skipped = 0,
            ),
        )
    }

    @Test
    fun zipSlipDirectoryEntryIsRejected() {
        val zip = buildArchive { directory("../up/") }
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), RecordingSink())
        assertThat(result).isEqualTo(
            BundleReadResult.Rejected(
                BundleRejection.UnsafeEntryName,
                accepted = 0,
                skipped = 0,
            ),
        )
    }

    @Test
    fun duplicateEntryNamesRejectTheBundleAfterTheFirstCopyWasDelivered() {
        val zip = buildArchiveWithDuplicateEntry("a.pkpass", "first".toByteArray(), "second".toByteArray())
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), sink)
        assertThat(result).isEqualTo(
            BundleReadResult.Rejected(
                BundleRejection.DuplicateEntryName,
                accepted = 1,
                skipped = 0,
            ),
        )
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
    fun invalidUtf8EntryNameIsATypedRejectionNotAThrow() {
        // ZipOutputStream sets the EFS flag, so ZipInputStream decodes the name as strict
        // UTF-8 and throws IllegalArgumentException on a bad byte instead of an IOException.
        val zip = corruptFirstNameByte(buildArchive { entry("a.pkpass", "A".toByteArray()) })
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), sink)
        assertThat(result).isEqualTo(
            BundleReadResult.Rejected(
                BundleRejection.NotAZipArchive,
                accepted = 0,
                skipped = 0,
            ),
        )
        assertThat(sink.names).isEmpty()
    }

    @Test
    fun truncationInsideALaterEntryRejectsAfterEarlierEntriesWereDelivered() {
        val zip =
            buildArchive {
                entry("a.pkpass", "A".toByteArray())
                entry("b.pkpass", ByteArray(4_096) { (it * 31).toByte() })
            }
        // Cut a few bytes into the second entry's deflate stream, past its local header.
        val truncated = zip.copyOf(findNthLocalHeaderOffset(zip, 2) + 30 + "b.pkpass".length + 16)
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Bytes(truncated), sink)
        assertThat(result).isEqualTo(
            BundleReadResult.Rejected(
                BundleRejection.NotAZipArchive,
                accepted = 1,
                skipped = 0,
            ),
        )
        assertThat(sink.names).containsExactly("a.pkpass")
    }

    @Test
    fun truncationInsideALaterHeaderReadsAsEndOfStream() {
        // ZipInputStream treats a short local header as end-of-stream, so this shape
        // completes with what came before it. Pinned so the behaviour is a known one.
        val zip =
            buildArchive {
                entry("a.pkpass", "A".toByteArray())
                entry("b.pkpass", "B".toByteArray())
            }
        val truncated = zip.copyOf(findNthLocalHeaderOffset(zip, 2) + 10)
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Bytes(truncated), sink)
        assertThat(result).isEqualTo(BundleReadResult.Completed(accepted = 1, skipped = 0))
        assertThat(sink.names).containsExactly("a.pkpass")
    }

    @Test
    fun callerStreamThatThrowsOnFirstReadIsATypedRejection() {
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Stream(ThrowingInputStream()), sink)
        assertThat(result).isEqualTo(
            BundleReadResult.Rejected(
                BundleRejection.NotAZipArchive,
                accepted = 0,
                skipped = 0,
            ),
        )
        assertThat(sink.names).isEmpty()
    }

    @Test
    fun nonAsciiEntryNameSniffsAsBundleAndIsDeliveredVerbatim() {
        val zip = buildArchive { entry("billet-\u00e9.pkpass", "x".toByteArray()) }
        assertThat(sniffPassBundle(zip)).isEqualTo(PassBundleSniff.Bundle)
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), sink)
        assertThat(result).isEqualTo(BundleReadResult.Completed(accepted = 1, skipped = 0))
        assertThat(sink.names).containsExactly("billet-\u00e9.pkpass")
    }

    @Test
    fun sinkStopFromASkippedEntryHaltsDelivery() {
        val zip =
            buildArchive {
                entry("junk.txt", "j".toByteArray())
                entry("a.pkpass", "A".toByteArray())
            }
        val sink = RecordingSink(stopAfter = 1)
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), sink)
        assertThat(result).isEqualTo(BundleReadResult.Stopped(accepted = 0, skipped = 1))
        assertThat(sink.names).containsExactly("junk.txt")
    }

    @Test
    fun defaultAllowlistRequiresANonEmptyStem() {
        assertThat(BundleEntryAllowlist.PkpassOnly.accepts(".pkpass")).isFalse()
        assertThat(BundleEntryAllowlist.PkpassOnly.accepts("a.pkpass")).isTrue()
        val zip = buildArchive { entry(".pkpass", "x".toByteArray()) }
        assertThat(sniffPassBundle(zip)).isEqualTo(PassBundleSniff.NotBundle)
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), sink)
        assertThat(result).isEqualTo(BundleReadResult.Completed(accepted = 0, skipped = 1))
        assertThat(sink.skippedNames).containsExactly(".pkpass")
    }

    @Test
    fun allowlistExceptionsPropagateToTheCaller() {
        val zip = buildArchive { entry("a.pkpass", "A".toByteArray()) }
        val boom = IllegalStateException("allowlist failed")
        val reader = PassBundleReader.create(BundleConfig(allowlist = BundleEntryAllowlist { throw boom }))
        val thrown = runCatching { reader.read(PassSource.Bytes(zip), RecordingSink()) }.exceptionOrNull()
        assertThat(thrown).isSameInstanceAs(boom)
    }

    @Test
    fun entryOfExactlyThePerEntryCapIsDelivered() {
        val zip = buildArchive { entry("a.pkpass", ByteArray(4_096)) }
        val sink = RecordingSink()
        val result = PassBundleReader.create(BundleConfig(maxEntryBytes = 4_096)).read(PassSource.Bytes(zip), sink)
        assertThat(result).isEqualTo(BundleReadResult.Completed(accepted = 1, skipped = 0))
        assertThat(sink.acceptedBytes.getValue("a.pkpass")).hasLength(4_096)
    }

    @Test
    fun entriesSummingToExactlyTheCumulativeCapAreDelivered() {
        val zip =
            buildArchive {
                entry("a.pkpass", ByteArray(4_096))
                entry("b.pkpass", ByteArray(4_096))
            }
        val config = BundleConfig(maxEntryBytes = 4_096, maxCumulativeBytes = 8_192)
        val result = PassBundleReader.create(config).read(PassSource.Bytes(zip), RecordingSink())
        assertThat(result).isEqualTo(BundleReadResult.Completed(accepted = 2, skipped = 0))
    }

    @Test
    fun outerArchiveCapTrippingMidStreamKeepsEntriesAlreadyDelivered() {
        val incompressible = ByteArray(16_384).also { Random(42).nextBytes(it) }
        val zip =
            buildArchive {
                entry("a.pkpass", "A".toByteArray())
                entry("b.pkpass", incompressible)
            }
        // No size hint, so only the streaming bound can trip; it does so inside the second entry.
        val config = BundleConfig(maxArchiveBytes = zip.size - 1_024L)
        val sink = RecordingSink()
        val result = PassBundleReader.create(config).read(PassSource.Stream(ByteArrayInputStream(zip)), sink)
        assertThat(result)
            .isEqualTo(
                BundleReadResult.Rejected(
                    BundleRejection.LimitExceeded(BundleLimit.ArchiveSize),
                    accepted = 1,
                    skipped = 0,
                ),
            )
        assertThat(sink.names).containsExactly("a.pkpass")
    }

    @Test
    fun defaultAllowlistFoldsCaseInAsciiOnly() {
        // U+212A (Kelvin sign) lower-cases to "k" under Unicode folding; the ISO-8859-1
        // header sniff would call this NotBundle, so the reader must not accept it either.
        val kelvin = "a.p\u212Apass"
        assertThat(BundleEntryAllowlist.PkpassOnly.accepts(kelvin)).isFalse()
        val zip = buildArchive { entry(kelvin, "x".toByteArray()) }
        assertThat(sniffPassBundle(zip)).isEqualTo(PassBundleSniff.NotBundle)
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Bytes(zip), sink)
        assertThat(result).isEqualTo(BundleReadResult.Completed(accepted = 0, skipped = 1))
        assertThat(sink.skippedNames).containsExactly(kelvin)
    }

    @Test
    fun declaredSizeOverOuterArchiveCapFailsFastWithoutReading() {
        val tracker = OpenTrackingInputStream(ByteArrayInputStream(ByteArray(0)))
        val config = BundleConfig(maxArchiveBytes = 1_024)
        val result =
            PassBundleReader.create(config)
                .read(PassSource.Stream(tracker, sizeHintBytes = 1_025), RecordingSink())
        assertThat(result)
            .isEqualTo(
                BundleReadResult.Rejected(
                    BundleRejection.LimitExceeded(BundleLimit.ArchiveSize),
                    accepted = 0,
                    skipped = 0,
                ),
            )
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
            .isEqualTo(
                BundleReadResult.Rejected(
                    BundleRejection.LimitExceeded(BundleLimit.ArchiveSize),
                    accepted = 0,
                    skipped = 0,
                ),
            )
    }

    @Test
    fun bytesSourceOverOuterArchiveCapFailsFast() {
        val zip = buildArchive { entry("a.pkpass", ByteArray(4_096) { it.toByte() }) }
        val result =
            PassBundleReader.create(BundleConfig(maxArchiveBytes = 64)).read(PassSource.Bytes(zip), RecordingSink())
        assertThat(result)
            .isEqualTo(
                BundleReadResult.Rejected(
                    BundleRejection.LimitExceeded(BundleLimit.ArchiveSize),
                    accepted = 0,
                    skipped = 0,
                ),
            )
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
        assertThat(result).isEqualTo(
            BundleReadResult.Rejected(
                BundleRejection.UnsafeEntryName,
                accepted = 2,
                skipped = 0,
            ),
        )
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
