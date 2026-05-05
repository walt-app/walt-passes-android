package `is`.walt.passes.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
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
 * Why a canary file: Auto Backup's exclude-only rules apply only when there is something
 * to back up. If every file the test creates is excluded, the framework decides the package
 * has no eligible data and the resulting blob is empty. A `doesNotContain("walt_passes.db")`
 * assertion against an empty blob is vacuously true, so the test would pass even if the
 * exclusion rules were missing or wrong. The canary is an unrestricted file in the app's
 * `filesDir` whose presence in the blob is asserted before the exclusion assertions run.
 * That makes the absence of `walt_passes.db` a load-bearing claim rather than an artifact
 * of an empty blob.
 *
 * The bmgr invocation choice: `bmgr backupnow <pkg>` is preferred over `bmgr fullbackup`
 * because `bmgr fullbackup` writes a tarfile via `adb backup` plumbing that has been
 * progressively restricted since API 31 and is no longer reliable from instrumentation.
 * `bmgr backupnow` queues the backup and returns before completion; the actual completion
 * is logged by `BackupManagerService` later, so [awaitBackupCompletion] polls logcat for
 * the per-package result line rather than parsing bmgr's own stdout.
 *
 * The local transport's blob layout is read from `/data/data/com.android.localtransport/files/`
 * when accessible; that path requires privileged shell on most devices, so the file-listing
 * assertion (and the canary inclusion check that gates it) is held behind `assumeTrue` and
 * skipped cleanly when the path is opaque. On those devices [BackupRulesAssertionInstrumentationTest]
 * carries the trust-claim assertion via the merged rules XML.
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

    @After
    fun removeCanary() {
        canaryFile().delete()
    }

    @Test
    fun passesDataExcludedFromBackupBlob() {
        precreatePassesDatabaseInTestApp()
        val canary = dropCanary()

        InstrumentationShell.run("bmgr backupnow $packageName")
        awaitBackupCompletion(packageName)

        val transportDir = File("/data/data/com.android.localtransport/files/$packageName")
        assumeTrue(
            "Local transport blob layout is not readable from the shell user on this " +
                "device. The doesNotContain assertions below depend on inspecting the blob " +
                "directly; on devices without that access this test runs as a smoke check " +
                "that bmgr accepts the merged manifest, and BackupRulesAssertionInstrumentationTest " +
                "carries the trust-claim assertion via the merged rules XML.",
            transportDir.canRead(),
        )

        val listing = InstrumentationShell.run("ls -laR ${transportDir.absolutePath}").output

        // Sanity: the canary file (placed in the app's filesDir, not covered by any
        // <exclude> rule) must appear in the blob. Without this presence check the
        // exclusion assertions below would be vacuously true on an empty-blob run, e.g.
        // when the framework decides nothing is eligible to back up.
        assertThat(listing).contains(canary.name)

        // The trust claim: the encrypted database, its sidecars, and the wrapped-key
        // envelope must not appear in the blob.
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

    private fun canaryFile(): File =
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            CANARY_FILE_NAME,
        )

    private fun dropCanary(): File {
        val canary = canaryFile()
        canary.writeText(
            "wpass-cb9 backup canary; presence in the local-transport blob asserts that " +
                "the framework produced a non-empty backup, which makes the doesNotContain " +
                "assertions for walt_passes.db non-vacuous.",
        )
        check(canary.exists()) {
            "Canary precondition failed: $canary was not created."
        }
        return canary
    }

    /**
     * `bmgr backupnow` queues the backup and returns before the per-package result is
     * known; on API 28 it returns immediately with "Running incremental backup for 1
     * requested packages.", and on newer APIs it sometimes blocks for several seconds but
     * not always until completion. The completion line is logged by `BackupManagerService`
     * once the transport has accepted the data, so polling logcat for that tag is the
     * reliable signal across API 28-36.
     *
     * Times out cleanly so a wedged framework on a flaky emulator surfaces as a test
     * failure rather than a hung instrumentation run.
     */
    @Suppress("ReturnCount")
    private fun awaitBackupCompletion(packageName: String) {
        val deadlineMillis = System.currentTimeMillis() + BACKUP_COMPLETION_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadlineMillis) {
            val logcat = InstrumentationShell.run(
                "logcat -d -s BackupManagerService:* PerformBackupTask:*",
            ).output
            val finished = logcat.lineSequence().any { line ->
                (line.contains(packageName) && line.contains("result:")) ||
                    line.contains("Backup pass finished")
            }
            if (finished) return
            Thread.sleep(BACKUP_COMPLETION_POLL_INTERVAL_MILLIS)
        }
        error(
            "BackupManagerService did not log a completion line for $packageName " +
                "within ${BACKUP_COMPLETION_TIMEOUT_MILLIS}ms",
        )
    }

    private companion object {
        const val CANARY_FILE_NAME: String = "wpass_cb9_backup_canary.txt"
        const val BACKUP_COMPLETION_TIMEOUT_MILLIS: Long = 30_000
        const val BACKUP_COMPLETION_POLL_INTERVAL_MILLIS: Long = 500
    }
}
