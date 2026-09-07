package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.internal.buildArchive
import org.junit.Test

/**
 * Locks the bundle-reader public surface that does not need a running reader: the
 * header sniff, the config defaults, the constants, and the telemetry flattening.
 */
class PassBundleSurfaceTest {
    @Test
    fun sniffRecognisesABundleFromTheFirstLocalHeader() {
        val bundle = buildArchive { entry("first.pkpass", "x".toByteArray()) }
        assertThat(sniffPassBundle(bundle.copyOf(PassBundleSniff.RECOMMENDED_HEADER_BYTES)))
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
    fun sniffAppliesTheSameRootOnlyRuleAsTheDefaultAllowlist() {
        assertThat(sniffPassBundle(buildArchive { entry("passes/1.pkpass", "x".toByteArray()) }))
            .isEqualTo(PassBundleSniff.NotBundle)
        assertThat(sniffPassBundle(buildArchive { entry("__MACOSX/._a.pkpass", "x".toByteArray()) }))
            .isEqualTo(PassBundleSniff.NotBundle)
        assertThat(sniffPassBundle(buildArchive { entry(".DS_Store", "x".toByteArray()) }))
            .isEqualTo(PassBundleSniff.NotBundle)
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
    fun limitFromReadsTheMatchingConfigField() {
        val cfg = BundleConfig(maxArchiveBytes = 1, maxEntries = 2, maxEntryBytes = 3, maxCumulativeBytes = 4)
        assertThat(BundleLimit.ArchiveSize.limitFrom(cfg)).isEqualTo(1L)
        assertThat(BundleLimit.EntryCount.limitFrom(cfg)).isEqualTo(2L)
        assertThat(BundleLimit.EntrySize.limitFrom(cfg)).isEqualTo(3L)
        assertThat(BundleLimit.CumulativeSize.limitFrom(cfg)).isEqualTo(4L)
    }

    @Test
    fun bundleRejectionFlattensToADistinctFailureReasonPerArm() {
        val all: List<BundleRejection> =
            listOf(
                BundleRejection.NotAZipArchive,
                BundleRejection.SourceUnreadable,
                BundleRejection.UnsafeEntryName,
                BundleRejection.DuplicateEntryName,
            ) + BundleLimit.entries.map { BundleRejection.LimitExceeded(it) }
        val mapped = all.map { it.toFailureReason() }
        assertThat(mapped).hasSize(all.size)
        assertThat(mapped.toSet()).containsExactlyElementsIn(BundleFailureReason.entries)
    }
}
