package `is`.walt.passes.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives `bmgr` end-to-end against the test APK and asserts that the Auto Backup pipeline
 * accepts the library's merged backup-rules contributions and runs to completion without
 * pulling the passes database into the resulting backup blob.
 *
 * What this test verifies, and what it deliberately does NOT:
 *
 *  - Verified: the merged manifest is syntactically valid input to the Backup Manager
 *    (`bmgr backupnow` does not abort with a manifest-rejection error), and
 *    the local-transport backup output, when reachable, does not list `walt_passes.db`,
 *    its WAL/journal sidecars, or the wrapped-key envelope sharedpref file.
 *  - NOT verified at this layer: that the Android backup framework actually honors valid
 *    rules. That is a guarantee of AOSP, exercised by AOSP's own CTS suite, not something
 *    this library can re-prove from instrumentation. The composition of this test plus
 *    [BackupRulesAssertionInstrumentationTest] (content-check of the merged rules) is
 *    sufficient for the trust claim.
 *
 * The bmgr invocation choice: `bmgr backupnow <pkg>` is preferred over `bmgr fullbackup`
 * because the former returns a structured "Result: Success" line that a test can parse
 * deterministically across API 28-36. `bmgr fullbackup` writes a tarfile via
 * `adb backup` plumbing that has been progressively restricted since API 31 and is no
 * longer reliable from instrumentation. The local transport's blob layout is read from
 * `/data/data/com.android.localtransport/files/` when accessible; that path requires
 * privileged shell on most devices, so the file-listing assertion is gated behind
 * `assumeTrue` and skipped cleanly when the path is opaque.
 */
@RunWith(AndroidJUnit4::class)
class AutoBackupBmgrTest {

    private val packageName: String =
        InstrumentationRegistry.getInstrumentation().targetContext.packageName

    @Before
    fun ensureBackupManagerIsReady() {
        // Backup framework must be enabled and pointed at the local transport for any
        // bmgr backupnow attempt to do real work. The shell user can flip these states.
        InstrumentationShell.run("bmgr enable true")
        InstrumentationShell.run("bmgr transport com.android.localtransport/.LocalTransport")
    }

    @Test
    fun bmgrBackupNowSucceedsAndExcludesPassesDatabase() {
        precreatePassesDatabaseInTestApp()

        val backup = InstrumentationShell.run("bmgr backupnow $packageName")
        // bmgr emits a per-package result line like:
        //   "Package com.example.pkg with result: Success"
        // older releases use "Backup finished with result: Success". Either form contains
        // the substring "Success" only on the success path; "no data to backup" surfaces
        // as a different terminal line.
        assertThat(backup.output).contains("Success")

        val transportDir = File("/data/data/com.android.localtransport/files/$packageName")
        assumeTrue(
            "Local transport blob layout is not readable from the shell user on this " +
                "device. The bmgr success line above is the load-bearing signal here; the " +
                "blob-listing assertion is best-effort and only fires on userdebug builds.",
            transportDir.canRead(),
        )

        val listing = InstrumentationShell.run("ls -laR ${transportDir.absolutePath}").output
        assertThat(listing).doesNotContain("walt_passes.db")
        assertThat(listing).doesNotContain("walt_passes.db-journal")
        assertThat(listing).doesNotContain("walt_passes.db-wal")
        assertThat(listing).doesNotContain("walt_passes.db-shm")
        assertThat(listing).doesNotContain("is.walt.passes.storage.key_envelope")
    }

    /**
     * Drives the storage stack far enough to materialize `walt_passes.db` (and the
     * envelope sharedpref) on disk, so that the bmgr backup pass has real files to
     * choose to include or exclude. Without this the absence of `walt_passes.db` in the
     * blob listing would be vacuously true.
     */
    private fun precreatePassesDatabaseInTestApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyResult = AndroidKeystorePassKeyProvider.create(context)
        check(keyResult is StorageResult.Success) {
            "Keystore-backed key provider failed to bootstrap: $keyResult"
        }
        val repoResult = SqlCipherPassRepository.create(
            context = context,
            keyProvider = keyResult.value,
            telemetryGuard = NoOpStorageTelemetryGuard,
        )
        check(repoResult is StorageResult.Success) {
            "SqlCipher repository failed to bootstrap: $repoResult"
        }
        repoResult.value.close()

        val dbFile = context.getDatabasePath(Schema.DATABASE_NAME)
        check(dbFile.exists()) {
            "Pre-backup precondition failed: $dbFile was not created."
        }
    }
}
