package `is`.walt.passes.storage

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.storage.BackupRulesAssertion.MissingSection
import `is`.walt.passes.storage.BackupRulesAssertion.Outcome
import `is`.walt.passes.storage.BackupRulesContract.REQUIRED_EXCLUDES
import `is`.walt.passes.storage.BackupRulesContract.RequiredExclude
import `is`.walt.passes.storage.BackupRulesContract.Section
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Behavioral tests for [BackupRulesAssertion]. The XML parser is exercised directly
 * against hand-crafted strings, which lets us cover both consumer postures (inherit vs
 * mirror), missing-entry diagnostics, and malformed-input handling without needing
 * test-only Android resources.
 *
 * The end-to-end Context-driven path is exercised against the library's own
 * `walt_passes_backup_rules.xml` and `walt_passes_data_extraction_rules.xml`, which
 * Robolectric loads via the library's manifest contributions. The on-device path
 * (StrongBox vs TEE matrix, bmgr backup-blob inspection) is covered by the `wpass-x5l`
 * instrumentation suite; this file stays JVM-only.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupRulesAssertionTest {

    @Test
    fun parsesFullBackupContentEntries() {
        val xml = """
            <full-backup-content>
                <exclude domain="database" path="walt_passes.db" />
                <exclude domain="database" path="walt_passes.db-journal" />
                <exclude domain="database" path="walt_passes.db-wal" />
                <exclude domain="database" path="walt_passes.db-shm" />
                <exclude domain="sharedpref" path="is.walt.passes.storage.key_envelope.xml" />
            </full-backup-content>
        """.trimIndent()

        val parsed = parse(xml)

        assertThat(parsed.keys).containsExactly(Section.FullBackupContent)
        assertThat(parsed.getValue(Section.FullBackupContent))
            .containsExactlyElementsIn(REQUIRED_EXCLUDES)
    }

    @Test
    fun parsesDataExtractionRulesIntoBothSections() {
        val xml = """
            <data-extraction-rules>
                <cloud-backup>
                    <exclude domain="database" path="walt_passes.db" />
                    <exclude domain="database" path="walt_passes.db-journal" />
                    <exclude domain="database" path="walt_passes.db-wal" />
                    <exclude domain="database" path="walt_passes.db-shm" />
                    <exclude domain="sharedpref" path="is.walt.passes.storage.key_envelope.xml" />
                </cloud-backup>
                <device-transfer>
                    <exclude domain="database" path="walt_passes.db" />
                    <exclude domain="database" path="walt_passes.db-journal" />
                    <exclude domain="database" path="walt_passes.db-wal" />
                    <exclude domain="database" path="walt_passes.db-shm" />
                    <exclude domain="sharedpref" path="is.walt.passes.storage.key_envelope.xml" />
                </device-transfer>
            </data-extraction-rules>
        """.trimIndent()

        val parsed = parse(xml)

        assertThat(parsed.keys).containsExactly(Section.CloudBackup, Section.DeviceTransfer)
        assertThat(parsed.getValue(Section.CloudBackup))
            .containsExactlyElementsIn(REQUIRED_EXCLUDES)
        assertThat(parsed.getValue(Section.DeviceTransfer))
            .containsExactlyElementsIn(REQUIRED_EXCLUDES)
    }

    @Test
    fun ignoresIncludeEntriesAndUnknownElements() {
        val xml = """
            <data-extraction-rules>
                <cloud-backup>
                    <include domain="database" path="other.db" />
                    <exclude domain="database" path="walt_passes.db" />
                    <unknown-element foo="bar" />
                </cloud-backup>
            </data-extraction-rules>
        """.trimIndent()

        val parsed = parse(xml)

        assertThat(parsed.getValue(Section.CloudBackup)).containsExactly(
            RequiredExclude("database", "walt_passes.db"),
        )
        assertThat(parsed.keys).doesNotContain(Section.DeviceTransfer)
    }

    @Test
    fun mirroredResourceWithExtraExcludesStillCarriesRequired() {
        // Walt-android's posture: own resource that mirrors required entries plus its own.
        val xml = """
            <data-extraction-rules>
                <cloud-backup>
                    <exclude domain="database" path="walt_passes.db" />
                    <exclude domain="database" path="walt_passes.db-journal" />
                    <exclude domain="database" path="walt_passes.db-wal" />
                    <exclude domain="database" path="walt_passes.db-shm" />
                    <exclude domain="sharedpref" path="is.walt.passes.storage.key_envelope.xml" />
                    <exclude domain="database" path="transactions.db" />
                    <exclude domain="sharedpref" path="walt_secrets.xml" />
                </cloud-backup>
                <device-transfer>
                    <exclude domain="database" path="walt_passes.db" />
                    <exclude domain="database" path="walt_passes.db-journal" />
                    <exclude domain="database" path="walt_passes.db-wal" />
                    <exclude domain="database" path="walt_passes.db-shm" />
                    <exclude domain="sharedpref" path="is.walt.passes.storage.key_envelope.xml" />
                </device-transfer>
            </data-extraction-rules>
        """.trimIndent()

        val parsed = parse(xml)

        assertThat(parsed.getValue(Section.CloudBackup))
            .containsAtLeastElementsIn(REQUIRED_EXCLUDES)
    }

    @Test
    fun absentSectionIsReportedAsAbsentByParser() {
        val xml = """
            <data-extraction-rules>
                <cloud-backup>
                    <exclude domain="database" path="walt_passes.db" />
                </cloud-backup>
            </data-extraction-rules>
        """.trimIndent()

        val parsed = parse(xml)

        assertThat(parsed.keys).containsExactly(Section.CloudBackup)
    }

    @Test
    fun unknownRootElementThrowsTypedException() {
        val xml = "<wrong-root><exclude domain=\"database\" path=\"x\" /></wrong-root>"
        try {
            parse(xml)
            error("expected BackupRulesParseException")
        } catch (expected: BackupRulesParseException) {
            assertThat(expected.message).contains("unexpected root element")
            assertThat(expected.message).contains("wrong-root")
        }
    }

    @Test
    fun excludeOutsideAnySectionIsIgnored() {
        // Defensive: a stray <exclude> directly under <data-extraction-rules> with no
        // <cloud-backup>/<device-transfer> wrapper has no semantics and is dropped.
        val xml = """
            <data-extraction-rules>
                <exclude domain="database" path="stray.db" />
            </data-extraction-rules>
        """.trimIndent()

        val parsed = parse(xml)
        assertThat(parsed).isEmpty()
    }

    @Test
    fun endToEndAgainstLibraryOwnRulesReturnsApplied() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outcome = BackupRulesAssertion.assertBackupRulesApplied(context)
        assertThat(outcome).isEqualTo(Outcome.Applied)
    }

    @Test
    fun appWideAllowBackupFalseReturnsAppliedWithoutParsingRules() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Clone the live ApplicationInfo via Parcel so the test's mutation cannot leak
        // into Robolectric's shared package state for sibling tests.
        val freshInfo = cloneApplicationInfo(context.applicationInfo).apply {
            flags = flags and ApplicationInfo.FLAG_ALLOW_BACKUP.inv()
        }
        val outcome = BackupRulesAssertion.assertBackupRulesApplied(freshInfo, context.resources)
        assertThat(outcome).isEqualTo(Outcome.Applied)
    }

    /**
     * Locks the [Outcome] surface against silent additions or removals. Matches the
     * project convention from `PublicApiSurfaceTest`: every sealed arm is reached via
     * an exhaustive `when` so that adding or removing an arm forces a compile-time
     * conversation here, before any consumer sees it.
     */
    @Test
    fun outcomeArmsAreReachableViaWhen() {
        val outcomes: List<Outcome> = listOf(
            Outcome.Applied,
            Outcome.MissingExcludes(
                listOf(
                    MissingSection(
                        resourceId = 0x7f0e0001,
                        section = Section.CloudBackup,
                        missing = listOf(RequiredExclude("database", "walt_passes.db")),
                    ),
                ),
            ),
            Outcome.NoBackupRulesConfigured,
            Outcome.RulesResourceUnreadable(resourceId = 0x7f0e0002, reason = "io"),
            Outcome.PackageInfoUnavailable,
            Outcome.FieldUnavailable,
        )
        val labels = outcomes.map { outcome ->
            when (outcome) {
                Outcome.Applied -> "applied"
                is Outcome.MissingExcludes -> "missing:${outcome.problems.size}"
                Outcome.NoBackupRulesConfigured -> "no-rules"
                is Outcome.RulesResourceUnreadable -> "unreadable:${outcome.reason}"
                Outcome.PackageInfoUnavailable -> "no-package"
                Outcome.FieldUnavailable -> "no-field"
            }
        }
        assertThat(labels).containsExactly(
            "applied",
            "missing:1",
            "no-rules",
            "unreadable:io",
            "no-package",
            "no-field",
        ).inOrder()
    }

    @Test
    fun sectionEnumeratesTheThreeKnownSectionsWithXmlElementNames() {
        assertThat(Section.entries.map { it.name }).containsExactly(
            "FullBackupContent",
            "CloudBackup",
            "DeviceTransfer",
        ).inOrder()
        assertThat(Section.FullBackupContent.xmlElement).isEqualTo("full-backup-content")
        assertThat(Section.CloudBackup.xmlElement).isEqualTo("cloud-backup")
        assertThat(Section.DeviceTransfer.xmlElement).isEqualTo("device-transfer")
    }

    private fun parse(xml: String): Map<Section, Set<RequiredExclude>> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        return parseRulesXml(parser)
    }

    private fun cloneApplicationInfo(source: ApplicationInfo): ApplicationInfo {
        val parcel = Parcel.obtain()
        return try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            ApplicationInfo.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
