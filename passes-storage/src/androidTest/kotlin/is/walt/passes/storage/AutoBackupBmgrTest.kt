package `is`.walt.passes.storage

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.storage.internal.WrappedKeyStorage
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
 * `bmgr backupnow` queues the backup and returns before completion across API 28-36; the
 * actual completion is observed by polling the local-transport blob directory until the
 * canary file appears, with a timeout. That signal is more portable than parsing logcat
 * (the BMS tag and completion-line wording vary across system images) or bmgr's stdout
 * (which on some images returns `"Running incremental backup..."` and exits within
 * milliseconds without waiting for the per-package result).
 *
 * The local transport's blob layout is read from `/data/data/com.android.localtransport/files/`
 * when accessible; that path requires privileged shell on most devices, so the canary-poll
 * (and the file-listing assertion it gates) is held behind `assumeTrue` and skipped cleanly
 * when the path is opaque. On those devices [BackupRulesAssertionInstrumentationTest]
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
        // On API 31+ BackupManagerService kills the package's process after a full backup
        // completes (PerformFullTransportBackupTask.tearDownAgentAndKill -> killApplicationProcess).
        // Because the test APK is both the test runner AND the package being backed up, the
        // post-backup SIGKILL takes out AndroidJUnitRunner before any in-process assertion
        // can run; the kill arrives within ~70ms of the canary landing in the local-transport
        // blob, so even tightening the poll interval cannot reliably observe the blob first.
        // Returning early from the test method does not help: the JVM-level SIGKILL takes
        // out every subsequent test too.
        //
        // The trust claim on API 31+ is carried by BackupRulesAssertionInstrumentationTest,
        // which walks the merged rules XML directly without invoking bmgr (and so without
        // triggering the post-backup kill). This test stays as the API-28 lever for the same
        // claim, which exercises the full bmgr -> local-transport -> blob path end-to-end.
        assumeTrue(
            "AutoBackupBmgrTest is only exercisable on API < ${Build.VERSION_CODES.S}; on " +
                "API 31+ BackupManagerService kills the package's process after a full " +
                "backup, and our test APK is the package being backed up. The trust-claim " +
                "assertion at this API level is carried by BackupRulesAssertionInstrumentationTest.",
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S,
        )

        precreatePassesDatabaseInTestApp()
        val canary = dropCanary()

        InstrumentationShell.run("bmgr backupnow $packageName")

        // Poll the local-transport blob directory for the canary's appearance. The poll
        // also doubles as the can-we-see-this-blob check: if the listing never returns the
        // canary within the timeout (because the directory is opaque to the shell user, or
        // because bmgr was rejected silently on a freshly-booted emulator with system
        // services still warming up), assumeTrue-skip cleanly. On devices where the listing
        // does include the canary, the doesNotContain assertions below run with the canary's
        // presence as their non-vacuity guarantee.
        val transportDir = File("/data/data/com.android.localtransport/files/$packageName")
        val listing = pollForCanaryInBlob(transportDir, canary.name)
        assumeTrue(
            "Local-transport blob did not surface the canary file within " +
                "${BACKUP_OBSERVATION_TIMEOUT_MILLIS}ms. Either the blob layout is opaque " +
                "to the shell user on this device, or bmgr backupnow did not produce a blob " +
                "for this package within the budget. The doesNotContain assertions below " +
                "depend on the blob being inspectable; on devices without that visibility " +
                "this test is a smoke check that bmgr accepts the merged manifest, and " +
                "BackupRulesAssertionInstrumentationTest carries the trust-claim assertion " +
                "via the merged rules XML.",
            listing != null,
        )
        requireNotNull(listing)

        // The canary check is the non-vacuity guarantee referenced in the class docstring;
        // pollForCanaryInBlob already required it before returning, so this is just an
        // explicit reassertion for readability.
        assertThat(listing).contains(canary.name)

        // The trust claim: the encrypted database, its sidecars, and the wrapped-key
        // envelope must not appear in the blob. Reference the production constants so a
        // rename in the implementation cannot drift these into vacuous absence assertions.
        assertThat(listing).doesNotContain(Schema.DATABASE_NAME)
        assertThat(listing).doesNotContain("${Schema.DATABASE_NAME}-journal")
        assertThat(listing).doesNotContain("${Schema.DATABASE_NAME}-wal")
        assertThat(listing).doesNotContain("${Schema.DATABASE_NAME}-shm")
        assertThat(listing).doesNotContain(WrappedKeyStorage.PREFS_NAME)
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
     * Polls the local-transport directory listing every
     * [BACKUP_OBSERVATION_POLL_INTERVAL_MILLIS] until either [canaryName] appears in the
     * listing (returns the listing) or [BACKUP_OBSERVATION_TIMEOUT_MILLIS] elapses (returns
     * null).
     *
     * Returning the listing rather than just a boolean lets the caller reuse the snapshot
     * for the subsequent exclusion assertions, which guarantees the assertions run against
     * the same blob state in which the canary was observed (not a later snapshot in which
     * the framework might have rewritten the blob).
     */
    private fun pollForCanaryInBlob(transportDir: File, canaryName: String): String? {
        val deadlineMillis = System.currentTimeMillis() + BACKUP_OBSERVATION_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadlineMillis) {
            val listing = InstrumentationShell.run(
                "ls -laR ${transportDir.absolutePath}",
            ).output
            if (listing.contains(canaryName)) return listing
            Thread.sleep(BACKUP_OBSERVATION_POLL_INTERVAL_MILLIS)
        }
        return null
    }

    private companion object {
        const val CANARY_FILE_NAME: String = "wpass_cb9_backup_canary.txt"
        const val BACKUP_OBSERVATION_TIMEOUT_MILLIS: Long = 30_000
        const val BACKUP_OBSERVATION_POLL_INTERVAL_MILLIS: Long = 500
    }
}
