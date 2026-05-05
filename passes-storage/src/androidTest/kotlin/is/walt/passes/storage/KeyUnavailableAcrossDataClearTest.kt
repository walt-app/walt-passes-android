package `is`.walt.passes.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.Barcode
import `is`.walt.passes.core.BarcodeFormat
import `is`.walt.passes.core.ColorValue
import `is`.walt.passes.core.LocalizedStrings
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassColors
import `is`.walt.passes.core.PassField
import `is`.walt.passes.core.PassFields
import `is`.walt.passes.core.PassLocale
import `is`.walt.passes.core.PassType
import `is`.walt.passes.core.SignatureStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * Pins the trust-claim failure-closed semantic from ADR 0002 D2: when the Keystore-wrapped
 * key custody is broken from under the app, the next bootstrap MUST surface
 * [StorageError.KeyUnavailable] rather than silently regenerating a key (which would
 * brick the encrypted database) or throwing an unhandled exception.
 *
 * Two equivalence-class scenarios are exercised because they reach the same
 * [StorageError.KeyUnavailable] outcome through different code paths:
 *
 *  1. **Envelope wiped, DB present.** Reproduces the partial-storage-loss case the
 *     `databaseFileExists()` guard in [AndroidKeystorePassKeyProvider] is written for.
 *  2. **Keystore alias dropped, envelope and DB present.** Reproduces the case where
 *     the Keystore master is gone (factory-reset partial, lock-screen credential removed
 *     on user-auth-bound keys, OS upgrade dropped the entry) and unwrap fails on a real
 *     Keystore instance, surfacing as `UnrecoverableKeyException` / `KeyStoreException`
 *     and translating to [StorageError.KeyUnavailable].
 *
 * The bead's "delete the data dir or use pm clear" suggestion does NOT itself reach
 * [StorageError.KeyUnavailable] — wiping both the DB AND the envelope leaves the bootstrap
 * with no DB file on disk, so the provider correctly generates a fresh key. The two
 * scenarios above are the actual storage-clear surfaces that need to fail closed.
 */
@RunWith(AndroidJUnit4::class)
class KeyUnavailableAcrossDataClearTest {

    @Before
    fun resetStorage() {
        wipeStorageState()
    }

    @After
    fun cleanupStorage() {
        wipeStorageState()
    }

    @Test
    fun envelopeWipedWhileDatabaseFilePresentSurfacesKeyUnavailable() {
        bootstrapAndWriteOnePass()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = context.getDatabasePath(Schema.DATABASE_NAME)
        check(dbFile.exists()) { "Test setup left no DB file at $dbFile" }
        deleteEnvelopeFile(context)

        val keyResult = AndroidKeystorePassKeyProvider.create(context)
        check(keyResult is StorageResult.Success) {
            "Keystore master alias unexpectedly missing after envelope wipe: $keyResult"
        }
        val repoResult = SqlCipherPassRepository.create(
            context = context,
            keyProvider = keyResult.value,
            telemetryGuard = NoOpStorageTelemetryGuard,
        )
        check(repoResult is StorageResult.Failure) {
            "Re-bootstrap after envelope wipe must fail closed; got: $repoResult"
        }
        assertThat(repoResult.error).isEqualTo(StorageError.KeyUnavailable)
    }

    @Test
    fun keystoreAliasDroppedWhileEnvelopePresentSurfacesKeyUnavailable() {
        bootstrapAndWriteOnePass()

        deleteKeystoreMasterAlias()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyResult = AndroidKeystorePassKeyProvider.create(context)
        // create() may either re-generate a fresh master and then fail to unwrap the
        // existing envelope (KeyUnwrapFailed), or fail at master access (KeyUnavailable).
        // Both are acceptable failure-closed outcomes per ADR 0002 D7; the trust claim
        // is "no silent corruption", not "exactly one error arm."
        when (keyResult) {
            is StorageResult.Failure ->
                assertThat(keyResult.error).isAnyOf(
                    StorageError.KeyUnavailable,
                    StorageError.KeyUnwrapFailed,
                )
            is StorageResult.Success -> {
                val repoResult = SqlCipherPassRepository.create(
                    context = context,
                    keyProvider = keyResult.value,
                    telemetryGuard = NoOpStorageTelemetryGuard,
                )
                check(repoResult is StorageResult.Failure) {
                    "Re-bootstrap after Keystore alias drop must fail closed; got: $repoResult"
                }
                assertThat(repoResult.error).isAnyOf(
                    StorageError.KeyUnavailable,
                    StorageError.KeyUnwrapFailed,
                )
            }
        }
    }

    private fun bootstrapAndWriteOnePass() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyResult = AndroidKeystorePassKeyProvider.create(context)
        check(keyResult is StorageResult.Success) {
            "First-time bootstrap of Keystore key provider failed: $keyResult"
        }
        val repoResult = SqlCipherPassRepository.create(
            context = context,
            keyProvider = keyResult.value,
            telemetryGuard = NoOpStorageTelemetryGuard,
        )
        check(repoResult is StorageResult.Success) {
            "First-time bootstrap of SqlCipher repo failed: $repoResult"
        }
        val repo = repoResult.value
        runBlocking {
            val upsertOutcome = repo.upsert(buildSamplePass(), SignatureStatus.AppleVerified)
            check(upsertOutcome is StorageResult.Success) {
                "Sample pass upsert failed: $upsertOutcome"
            }
        }
        repo.close()
    }

    private fun deleteEnvelopeFile(context: android.content.Context) {
        // SharedPreferences XMLs live at /data/data/<pkg>/shared_prefs/<name>.xml.
        // Direct file removal is the cleanest reproduction of the "envelope vanished"
        // scenario; clearing the prefs through the SharedPreferences API would emit
        // listeners and could leave the file on disk (even if empty).
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val envelope = File(prefsDir, "is.walt.passes.storage.key_envelope.xml")
        envelope.delete()
        check(!envelope.exists()) {
            "Envelope file at $envelope was not deletable; cannot run failure-closed assertion"
        }
    }

    private fun deleteKeystoreMasterAlias() {
        // Reaches into the same AndroidKeyStore that AndroidKeystorePassKeyProvider uses
        // and removes the master alias. Real-world equivalent: lock-screen credential
        // removed on a setup that bound the master to user authentication, OS upgrade
        // dropped the entry, factory-reset-but-data-restored.
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keystore.containsAlias(AndroidKeystorePassKeyProvider.MASTER_KEY_ALIAS)) {
            keystore.deleteEntry(AndroidKeystorePassKeyProvider.MASTER_KEY_ALIAS)
        }
    }

    private fun wipeStorageState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getDatabasePath(Schema.DATABASE_NAME).also { db ->
            db.delete()
            File(db.absolutePath + "-journal").delete()
            File(db.absolutePath + "-wal").delete()
            File(db.absolutePath + "-shm").delete()
        }
        deleteEnvelopeFile(context)
        deleteKeystoreMasterAlias()
    }

    private fun buildSamplePass(): Pass = Pass(
        type = PassType.BoardingPass,
        serialNumber = "x5l-test-serial",
        description = "Sample pass for KeyUnavailable round-trip",
        organizationName = "Walt Tests",
        expirationDate = null,
        voided = false,
        colors = PassColors(
            foreground = ColorValue(0xFFFFFF),
            background = ColorValue(0x000000),
            label = null,
        ),
        frontFields = PassFields(
            primary = listOf(PassField(key = "p", label = null, value = "v")),
        ),
        backFields = emptyList(),
        barcode = Barcode(
            format = BarcodeFormat.QR,
            message = "x5l-instrumentation",
            messageEncoding = "iso-8859-1",
            altText = null,
        ),
        images = emptyMap(),
        locales = mapOf(PassLocale("en") to LocalizedStrings(emptyMap())),
    )
}
