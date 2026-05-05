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
            error("expected BackupRulesXmlParser.ParseException")
        } catch (expected: BackupRulesXmlParser.ParseException) {
            assertThat(expected.message).contains("unexpected root element")
            assertThat(expected.message).contains("wrong-root")
        }
    }

    @Test
    fun duplicateSectionsAreUnioned() {
        // Defensive: a malformed document repeating <cloud-backup> must not mask a
        // missing entry in the first block by its presence in the second.
        val xml = """
            <data-extraction-rules>
                <cloud-backup>
                    <exclude domain="database" path="walt_passes.db" />
                </cloud-backup>
                <cloud-backup>
                    <exclude domain="database" path="walt_passes.db-journal" />
                </cloud-backup>
            </data-extraction-rules>
        """.trimIndent()

        val parsed = parse(xml)

        assertThat(parsed.getValue(Section.CloudBackup)).containsExactly(
            RequiredExclude("database", "walt_passes.db"),
            RequiredExclude("database", "walt_passes.db-journal"),
        )
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
        // The test must exercise the parser path, not the allowBackup=false short-circuit.
        // If the library's manifest is ever changed to flip this flag, this assertion
        // surfaces the change rather than the test silently passing for the wrong reason.
        assertThat(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
            .isNotEqualTo(0)

        val publicOutcome = BackupRulesAssertion.assertBackupRulesApplied(context)
        // Cross-check: the public entry point and the test seam must agree on the same
        // unmutated context. Catches divergence between Context.applicationInfo and
        // packageManager.getApplicationInfo(packageName) under any future Robolectric
        // configuration.
        val seamOutcome = BackupRulesAssertion.assertBackupRulesApplied(
            context.applicationInfo,
            context.resources,
        )

        assertThat(publicOutcome).isEqualTo(Outcome.Applied)
        assertThat(seamOutcome).isEqualTo(publicOutcome)
    }

    @Test
    @Config(sdk = [30])
    fun endToEndOnPreSReturnsAppliedFromFullBackupContentAlone() {
        // Pre-S devices use full-backup-content, not data-extraction-rules. This test
        // pins that the legacy path of the trust claim works end-to-end on Android 11
        // and earlier, where ApplicationInfo.dataExtractionRulesRes does not exist.
        // Hardened the same way as the API 34 endToEnd test so a future allowBackup
        // flip surfaces here regardless of which Android version regresses first.
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertThat(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
            .isNotEqualTo(0)

        val publicOutcome = BackupRulesAssertion.assertBackupRulesApplied(context)
        val seamOutcome = BackupRulesAssertion.assertBackupRulesApplied(
            context.applicationInfo,
            context.resources,
        )

        assertThat(publicOutcome).isEqualTo(Outcome.Applied)
        assertThat(seamOutcome).isEqualTo(publicOutcome)
    }

    @Test
    fun reflectionSucceedsAndManifestFallbackIsNotInvoked() {
        // First tier of the read order: when reflection on ApplicationInfo returns a
        // valid ResourceIds, the assertion must pass through to validateResource without
        // invoking the manifest-XML fallback. Pins the documented "reflection first,
        // manifest second" order so a future refactor cannot accidentally promote the
        // (slower) fallback to primary.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ids = BackupRulesAssertion.ResourceIds(
            fullBackup = R.xml.walt_passes_backup_rules,
            dxr = R.xml.walt_passes_data_extraction_rules,
        )
        var manifestCalls = 0
        val outcome = BackupRulesAssertion.assertBackupRulesApplied(
            appInfo = context.applicationInfo,
            resources = context.resources,
            reflectionReader = { ids },
            manifestFallbackReader = { _, _ -> manifestCalls++; null },
        )

        assertThat(outcome).isEqualTo(Outcome.Applied)
        assertThat(manifestCalls).isEqualTo(0)
    }

    @Test
    fun reflectionUnavailableTriggersManifestFallbackAndReturnsApplied() {
        // Realises the simulated state PR #20 surfaced on real hardware: reflection on
        // ApplicationInfo.dataExtractionRulesRes throws NoSuchFieldException under the
        // hidden-API blocklist, so the first-tier reader returns null. The second-tier
        // manifest-XML reader resolves the same resource ids and the assertion returns
        // Applied — "degrade from broken to slower, not broken to manual review."
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ids = BackupRulesAssertion.ResourceIds(
            fullBackup = R.xml.walt_passes_backup_rules,
            dxr = R.xml.walt_passes_data_extraction_rules,
        )
        val outcome = BackupRulesAssertion.assertBackupRulesApplied(
            appInfo = context.applicationInfo,
            resources = context.resources,
            reflectionReader = { null },
            manifestFallbackReader = { _, _ -> ids },
        )

        assertThat(outcome).isEqualTo(Outcome.Applied)
    }

    @Test
    fun reflectionAndManifestFallbackBothFailSurfacesFieldUnavailable() {
        // Both tiers exhausted: reflection blocklisted AND manifest read errored
        // (e.g., AXML malformed, or no cookie probe matched the running package).
        // The Outcome.FieldUnavailable surface remains the triage signal for "the OS
        // made every read path go away."
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outcome = BackupRulesAssertion.assertBackupRulesApplied(
            appInfo = context.applicationInfo,
            resources = context.resources,
            reflectionReader = { null },
            manifestFallbackReader = { _, _ -> null },
        )

        assertThat(outcome).isEqualTo(Outcome.FieldUnavailable)
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

    /**
     * Same project convention as [outcomeArmsAreReachableViaWhen] (and
     * `PublicApiSurfaceTest`): pin the enum surface so additions or removals require a
     * compile-time conversation here.
     */
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
        return BackupRulesXmlParser.parseRulesXml(parser)
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
