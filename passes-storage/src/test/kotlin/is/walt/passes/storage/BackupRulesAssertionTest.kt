package `is`.walt.passes.storage

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.storage.BackupRulesAssertion.MissingSection
import `is`.walt.passes.storage.BackupRulesAssertion.Outcome
import `is`.walt.passes.storage.BackupRulesAssertion.RequiredExclude
import `is`.walt.passes.storage.BackupRulesAssertion.Section
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Behavioral tests for [BackupRulesAssertion]. The XML parsing logic is exercised
 * directly via [BackupRulesAssertion.parseRulesXml] against hand-crafted strings, which
 * lets us cover both consumer postures (inherit vs mirror), missing-entry diagnostics,
 * and malformed-input handling without needing test-only Android resources.
 *
 * The end-to-end Context-driven path is exercised against the library's own
 * `walt_passes_backup_rules.xml` and `walt_passes_data_extraction_rules.xml`, which
 * Robolectric loads via the library's manifest contributions. The on-device path
 * (StrongBox vs TEE matrix, bmgr backup-blob inspection) is covered by the
 * `wpass-x5l` instrumentation suite; this file stays JVM-only.
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
            .containsExactlyElementsIn(BackupRulesAssertion.REQUIRED_EXCLUDES)
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
            .containsExactlyElementsIn(BackupRulesAssertion.REQUIRED_EXCLUDES)
        assertThat(parsed.getValue(Section.DeviceTransfer))
            .containsExactlyElementsIn(BackupRulesAssertion.REQUIRED_EXCLUDES)
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
    fun mirroredResourceWithExtraExcludesStillPasses() {
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
            .containsAtLeastElementsIn(BackupRulesAssertion.REQUIRED_EXCLUDES)
    }

    @Test
    fun missingDeviceTransferSectionIsReportedByCallerLogic() {
        // The parser itself doesn't synthesize sections; it only returns what's there.
        // The assertion treats absent sections as empty, which lets the assertion
        // surface them as missing-required-excludes.
        val xml = """
            <data-extraction-rules>
                <cloud-backup>
                    <exclude domain="database" path="walt_passes.db" />
                    <exclude domain="database" path="walt_passes.db-journal" />
                    <exclude domain="database" path="walt_passes.db-wal" />
                    <exclude domain="database" path="walt_passes.db-shm" />
                    <exclude domain="sharedpref" path="is.walt.passes.storage.key_envelope.xml" />
                </cloud-backup>
            </data-extraction-rules>
        """.trimIndent()

        val parsed = parse(xml)

        assertThat(parsed.keys).containsExactly(Section.CloudBackup)
    }

    @Test
    fun unknownRootElementThrows() {
        val xml = "<wrong-root><exclude domain=\"database\" path=\"x\" /></wrong-root>"
        try {
            parse(xml)
            error("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertThat(expected.message).contains("unexpected root element")
        }
    }

    @Test
    fun excludeOutsideAnySectionIsIgnored() {
        // Defensive: an <exclude> directly under <data-extraction-rules> with no
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
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val outcome = BackupRulesAssertion.assertBackupRulesApplied(context)
        assertThat(outcome).isEqualTo(Outcome.Applied)
    }

    @Test
    fun appWideAllowBackupFalseReturnsAppliedWithoutParsingRules() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info: ApplicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        val originalFlags = info.flags
        try {
            info.flags = info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP.inv()
            val outcome = BackupRulesAssertion.assertBackupRulesApplied(context)
            assertThat(outcome).isEqualTo(Outcome.Applied)
        } finally {
            info.flags = originalFlags
        }
    }

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
    fun sectionEnumeratesTheThreeKnownSections() {
        assertThat(Section.entries.map { it.name }).containsExactly(
            "FullBackupContent",
            "CloudBackup",
            "DeviceTransfer",
        ).inOrder()
    }

    private fun parse(xml: String): Map<Section, Set<RequiredExclude>> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        return BackupRulesAssertion.parseRulesXml(parser)
    }
}
