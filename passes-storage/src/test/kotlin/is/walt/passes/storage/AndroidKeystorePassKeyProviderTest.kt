package `is`.walt.passes.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.storage.internal.WrappedKeyEnvelope
import `is`.walt.passes.storage.internal.WrappedKeyStorage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Behavioral tests for the bootstrap branches of [AndroidKeystorePassKeyProvider] that do
 * not require Keystore — driven through a fake [WrappedKeyStorage] and a constructed
 * provider so the file-existence guard from review item (3) is reachable on the JVM CI
 * host.
 *
 * The Keystore-touching paths (master key generation, AES-GCM wrap/unwrap, StrongBox vs
 * TEE detection) are exercised on-device by the follow-on `wpass-x5l` instrumentation
 * suite; this file only locks the orchestration around the wrapped storage.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidKeystorePassKeyProviderTest {

    @Test
    fun missingEnvelopeWithExistingDatabaseFileSurfacesKeyUnavailable() {
        val storage = FakeWrappedKeyStorage(initial = null)
        val provider = AndroidKeystorePassKeyProvider(
            masterKey = NoOpSecretKey,
            keyBacking = KeyBacking.Tee,
            wrappedKeyStorage = storage,
            databaseFileExists = { true },
        )
        val result = provider.provideDatabaseKey()
        check(result is StorageResult.Failure)
        assertThat(result.error).isEqualTo(StorageError.KeyUnavailable)
        // No fresh envelope was written; the existing-but-unrecoverable DB is left
        // untouched for the wallet's "secure storage was reset" remediation flow.
        assertThat(storage.writeCount).isEqualTo(0)
    }

    private class FakeWrappedKeyStorage(initial: WrappedKeyEnvelope?) : WrappedKeyStorage {
        private var envelope: WrappedKeyEnvelope? = initial
        var writeCount: Int = 0
            private set

        override fun read(): WrappedKeyEnvelope? = envelope
        override fun write(envelope: WrappedKeyEnvelope, backing: KeyBacking): Boolean {
            this.envelope = envelope
            writeCount++
            return true
        }
    }

    private object NoOpSecretKey : javax.crypto.SecretKey {
        override fun getAlgorithm(): String = "AES"
        override fun getFormat(): String = "RAW"
        override fun getEncoded(): ByteArray = ByteArray(32)
    }
}
