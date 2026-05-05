package `is`.walt.passes.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the trust claim "the merged manifest carries backup-exclusion
 * rules whose content covers every required pass-related path."
 *
 * The Robolectric counterpart in the JVM test suite drives [BackupRulesAssertion] through
 * synthetic [android.content.pm.ApplicationInfo] objects so the parser branches are
 * reachable without an APK on disk. This instrumentation test pulls the real merged
 * manifest off the on-device test APK and runs the assertion end-to-end. It catches
 * regressions the JVM suite cannot, e.g. AGP manifest-merge silently dropping the
 * library's `<application android:fullBackupContent="...">` contribution under a future
 * AGP/manifest-merger release.
 *
 * Posture: this test APK does NOT use `tools:replace` on the rules attributes (see
 * `src/androidTest/AndroidManifest.xml`). The assertion therefore sees the library's own
 * `walt_passes_backup_rules.xml` and `walt_passes_data_extraction_rules.xml` resources
 * directly. ADR 0002 D5's "Mirror" posture (the walt-android consumer's posture) is
 * exercised separately by walt-android's own instrumentation suite.
 */
@RunWith(AndroidJUnit4::class)
class BackupRulesAssertionInstrumentationTest {

    @Test
    fun mergedManifestRulesAreApplied() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outcome = BackupRulesAssertion.assertBackupRulesApplied(context)
        assertThat(outcome).isEqualTo(BackupRulesAssertion.Outcome.Applied)
    }
}
