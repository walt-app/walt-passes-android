package `is`.walt.passes.storage

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import `is`.walt.passes.storage.internal.WrappedKeyEnvelope
import `is`.walt.passes.storage.internal.WrappedKeyStorage
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Android-only `PassKeyProvider`. Owns the master AES-256 key in Android Keystore (StrongBox
 * preferred, TEE fallback, software last) and the AES-GCM-wrapped 32-byte database key it
 * produces.
 *
 * The master key is non-exportable by Keystore design; the wrapping construction is the
 * bridge between "key never leaves hardware" and "SQLCipher needs raw bytes for its page-key
 * derivation." The unwrapped database key is held in process memory only inside the
 * [DatabaseKey] returned here; [DatabaseKey.withBytes] zeros its internal buffer on return.
 *
 * The constructor is internal; the only construction path is [create].
 */
public class AndroidKeystorePassKeyProvider internal constructor(
    private val masterKey: SecretKey,
    override val keyBacking: KeyBacking,
    private val wrappedKeyStorage: WrappedKeyStorage,
    private val databaseFileExists: () -> Boolean,
) : PassKeyProvider {

    override fun provideDatabaseKey(): StorageResult<DatabaseKey> {
        val existing = wrappedKeyStorage.read()
        return try {
            if (existing != null) {
                val raw = unwrap(existing)
                StorageResult.Success(DatabaseKey(raw))
            } else {
                // Envelope is missing. If the encrypted DB still exists on disk, the prior
                // DB key is unrecoverable: generating a fresh one would silently surface
                // later as DatabaseCorrupt when SQLCipher rejects the wrong key. Surface
                // KeyUnavailable so the wallet can guide the user through "secure storage
                // was reset; re-import passes" instead.
                if (databaseFileExists()) {
                    return StorageResult.Failure(StorageError.KeyUnavailable)
                }
                val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val envelope = wrap(raw)
                if (!wrappedKeyStorage.write(envelope, keyBacking)) {
                    // Critical: do NOT proceed to open SQLCipher if the envelope did not
                    // durably reach disk. A key that lives only in process memory would
                    // brick the DB on the next launch.
                    raw.fill(0)
                    return StorageResult.Failure(StorageError.KeyUnavailable)
                }
                StorageResult.Success(DatabaseKey(raw))
            }
        } catch (e: javax.crypto.AEADBadTagException) {
            StorageResult.Failure(StorageError.KeyUnwrapFailed)
        } catch (e: java.security.UnrecoverableKeyException) {
            StorageResult.Failure(StorageError.KeyUnavailable)
        } catch (e: java.security.KeyStoreException) {
            StorageResult.Failure(StorageError.KeyUnavailable)
        }
    }

    private fun wrap(raw: ByteArray): WrappedKeyEnvelope {
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val ciphertext = cipher.doFinal(raw)
        return WrappedKeyEnvelope(
            ciphertext = ciphertext,
            iv = cipher.iv,
            keyAlias = MASTER_KEY_ALIAS,
        )
    }

    private fun unwrap(envelope: WrappedKeyEnvelope): ByteArray {
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_BITS, envelope.iv)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
        return cipher.doFinal(envelope.ciphertext)
    }

    public companion object {
        internal const val MASTER_KEY_ALIAS: String = "is.walt.passes.storage.master_key.v1"
        internal const val GCM_TRANSFORMATION: String = "AES/GCM/NoPadding"
        internal const val GCM_TAG_BITS: Int = 128

        /**
         * Provisions the master Keystore alias (creating it on first call) and returns a
         * provider ready for [PassKeyProvider.provideDatabaseKey]. Reports
         * [StorageError.KeyUnavailable] if the AndroidKeyStore service refuses to load
         * (Keystore wiped, lock-screen credential removed on a setup that bound keys to it).
         */
        @JvmStatic
        public fun create(context: Context): StorageResult<AndroidKeystorePassKeyProvider> {
            val dbFile = context.applicationContext.getDatabasePath(Schema.DATABASE_NAME)
            return createInternal(
                wrappedKeyStorage = WrappedKeyStorage.sharedPreferences(context),
                databaseFileExists = { dbFile.exists() },
            )
        }

        internal fun createInternal(
            wrappedKeyStorage: WrappedKeyStorage,
            databaseFileExists: () -> Boolean,
        ): StorageResult<AndroidKeystorePassKeyProvider> {
            return try {
                val (key, backing) = getOrCreateMasterKey()
                StorageResult.Success(
                    AndroidKeystorePassKeyProvider(
                        masterKey = key,
                        keyBacking = backing,
                        wrappedKeyStorage = wrappedKeyStorage,
                        databaseFileExists = databaseFileExists,
                    ),
                )
            } catch (e: java.security.KeyStoreException) {
                StorageResult.Failure(StorageError.KeyUnavailable)
            } catch (e: java.security.UnrecoverableKeyException) {
                StorageResult.Failure(StorageError.KeyUnavailable)
            } catch (e: java.security.NoSuchProviderException) {
                StorageResult.Failure(StorageError.KeyUnavailable)
            }
        }

        private fun getOrCreateMasterKey(): Pair<SecretKey, KeyBacking> {
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = keystore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
            val key: SecretKey = existing ?: generateMasterKey()
            return key to detectBacking(key)
        }

        private fun generateMasterKey(): SecretKey {
            // StrongBox is best-effort. P preferred (API 28+); on devices that advertise the
            // feature but actually fail, fall back to TEE. The library never refuses to run
            // on emulators or Software-only Keystore implementations: doing so would brick
            // the wallet during development.
            val canRequestStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            return try {
                generateWithSpec(strongBoxBacked = canRequestStrongBox)
            } catch (e: StrongBoxUnavailableException) {
                // KeyGenerator state after a failed init() is undefined per the Keystore
                // contract; use a fresh instance for the fallback so we are not relying on
                // implementation-defined recovery behavior.
                generateWithSpec(strongBoxBacked = false)
            }
        }

        private fun generateWithSpec(strongBoxBacked: Boolean): SecretKey {
            val builder = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
            if (strongBoxBacked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(true)
            }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(builder.build())
            return gen.generateKey()
        }

        private fun detectBacking(key: SecretKey): KeyBacking {
            return try {
                val factory = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    when (info.securityLevel) {
                        KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyBacking.StrongBox
                        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyBacking.Tee
                        KeyProperties.SECURITY_LEVEL_SOFTWARE,
                        KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE,
                        KeyProperties.SECURITY_LEVEL_UNKNOWN -> KeyBacking.Software
                        else -> KeyBacking.Software
                    }
                } else {
                    @Suppress("DEPRECATION")
                    if (info.isInsideSecureHardware) KeyBacking.Tee else KeyBacking.Software
                }
            } catch (e: Throwable) {
                KeyBacking.Software
            }
        }
    }
}
