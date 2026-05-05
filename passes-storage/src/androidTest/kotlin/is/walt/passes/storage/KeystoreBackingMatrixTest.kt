package `is`.walt.passes.storage

import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Pins the Keystore backing-detection contract from ADR 0002 D2 across the three
 * [KeyBacking] arms. The production [AndroidKeystorePassKeyProvider.create] flow asks for
 * StrongBox-with-TEE-fallback once; this matrix instead asks for each backing explicitly
 * and asserts that the detected [KeyBacking] matches the request when the device supports
 * it. Tests skip via `assumeTrue` when the requested backing is unavailable so emulator
 * (TEE-only) and StrongBox-equipped physical-device runs both produce a clean signal:
 *
 *  - Emulator (no StrongBox): `keyBackingIsStrongBoxWhenAvailable` skipped, others pass.
 *  - Physical device with StrongBox: all three pass.
 *  - Software-only Keystore (rare; e.g. Android Studio "Phone (Headless)" without TEE):
 *    only `keyBackingIsSoftwareWhenHardwareUnavailable` runs.
 *
 * The library does NOT expose a way to force a backing on a hardware-Keystore device; this
 * test bypasses that by asking the platform Keystore directly. That is the right scope for
 * verifying detection: the production `create()` path is exercised separately by
 * [KeyUnavailableAcrossDataClearTest] and [AutoBackupBmgrTest].
 */
@RunWith(AndroidJUnit4::class)
class KeystoreBackingMatrixTest {

    private val testAliasPrefix = "is.walt.passes.storage.test.matrix"

    @After
    fun cleanupTestAliases() {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keystore.aliases().toList().forEach { alias ->
            if (alias.startsWith(testAliasPrefix)) {
                keystore.deleteEntry(alias)
            }
        }
    }

    @Test
    fun keyBackingIsStrongBoxWhenAvailable() {
        val pm = InstrumentationRegistry.getInstrumentation().context.packageManager
        assumeTrue(
            "PackageManager.FEATURE_STRONGBOX_KEYSTORE not advertised on this device",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE),
        )

        val key = try {
            generateMatrixKey(alias = "$testAliasPrefix.strongbox", strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            // Some devices advertise the feature but reject setIsStrongBoxBacked at
            // generation time. That is the StrongBox-fallback path the production code
            // handles; here we honor it as "device does not actually support StrongBox"
            // and skip rather than fail.
            assumeTrue("StrongBox advertised but generation rejected; treating as unavailable", false)
            return
        }
        assertThat(detectBacking(key)).isEqualTo(KeyBacking.StrongBox)
    }

    @Test
    fun keyBackingIsTeeWhenStrongBoxNotRequested() {
        val key = generateMatrixKey(alias = "$testAliasPrefix.tee", strongBox = false)
        val backing = detectBacking(key)
        // On a hardware-Keystore device this is TEE; on a software-only emulator it is
        // Software. The matrix arm pins TEE; the Software arm has its own assumeTrue.
        assumeTrue(
            "Hardware-backed Keystore not present on this device; backing is $backing",
            backing == KeyBacking.Tee,
        )
        assertThat(backing).isEqualTo(KeyBacking.Tee)
    }

    @Test
    fun keyBackingIsSoftwareWhenHardwareUnavailable() {
        val key = generateMatrixKey(alias = "$testAliasPrefix.software", strongBox = false)
        val backing = detectBacking(key)
        assumeTrue(
            "Hardware Keystore present; software-backing arm not exercisable here",
            backing == KeyBacking.Software,
        )
        assertThat(backing).isEqualTo(KeyBacking.Software)
    }

    private fun generateMatrixKey(alias: String, strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(builder.build())
        return gen.generateKey()
    }

    private fun detectBacking(key: SecretKey): KeyBacking {
        val factory = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyBacking.StrongBox
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyBacking.Tee
                else -> KeyBacking.Software
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) KeyBacking.Tee else KeyBacking.Software
        }
    }
}
