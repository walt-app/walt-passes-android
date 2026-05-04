package `is`.walt.passes.storage.internal

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import `is`.walt.passes.storage.KeyBacking

/**
 * Persists the AES-GCM-wrapped database-key envelope. ADR 0002 D2 places this in the
 * `schema_meta` table; in practice schema_meta lives inside the SQLCipher-encrypted
 * database, so the bootstrap envelope (which IS the key to the DB) must live outside it.
 *
 * Implementation: a private SharedPreferences file scoped to the wallet's package.
 * The envelope is itself ciphertext (AES-GCM wrapped by a Keystore-resident master key),
 * so plain SharedPreferences is acceptable storage; the threat model is "physical recovery
 * of disk bytes," and the wrapping defends against that without help from the prefs file.
 *
 * The envelope is excluded from Auto Backup via the same `walt_passes_backup_rules.xml` /
 * `walt_passes_data_extraction_rules.xml` files (the SharedPreferences file is opted out
 * by domain).
 */
internal interface WrappedKeyStorage {
    fun read(): WrappedKeyEnvelope?

    /**
     * Persists [envelope] synchronously and durably. Returns true on success, false if the
     * underlying storage rejected the write. Callers MUST treat a false return as a hard
     * failure: the brick scenario from the ADR class-of-bugs notes is "DB encrypted with a
     * key whose envelope never made it to disk".
     */
    fun write(envelope: WrappedKeyEnvelope, backing: KeyBacking): Boolean

    companion object {
        fun sharedPreferences(context: Context): WrappedKeyStorage =
            SharedPrefsWrappedKeyStorage(
                context.applicationContext.getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE,
                ),
            )

        const val PREFS_NAME: String = "is.walt.passes.storage.key_envelope"
    }
}

internal data class WrappedKeyEnvelope(
    val ciphertext: ByteArray,
    val iv: ByteArray,
    val keyAlias: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is WrappedKeyEnvelope &&
            ciphertext.contentEquals(other.ciphertext) &&
            iv.contentEquals(other.iv) &&
            keyAlias == other.keyAlias)

    override fun hashCode(): Int {
        var r = ciphertext.contentHashCode()
        r = 31 * r + iv.contentHashCode()
        r = 31 * r + keyAlias.hashCode()
        return r
    }
}

private class SharedPrefsWrappedKeyStorage(
    private val prefs: SharedPreferences,
) : WrappedKeyStorage {

    override fun read(): WrappedKeyEnvelope? {
        val ct = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val alias = prefs.getString(KEY_ALIAS, null) ?: return null
        return WrappedKeyEnvelope(
            ciphertext = Base64.decode(ct, Base64.NO_WRAP),
            iv = Base64.decode(iv, Base64.NO_WRAP),
            keyAlias = alias,
        )
    }

    override fun write(envelope: WrappedKeyEnvelope, backing: KeyBacking): Boolean {
        // Synchronous write. The envelope MUST be on disk before we hand the unwrapped
        // database key to SQLCipher: a crash between an async apply() and the disk flush
        // would leave the SQLCipher pages encrypted with a key whose envelope was never
        // persisted, bricking the wallet's at-rest data on next launch. commit() blocks
        // until the SharedPreferences XML is fsync'd.
        return prefs.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(envelope.iv, Base64.NO_WRAP))
            .putString(KEY_ALIAS, envelope.keyAlias)
            .putString(KEY_BACKING, backing.name)
            .commit()
    }

    private companion object {
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_IV = "iv"
        const val KEY_ALIAS = "alias"
        const val KEY_BACKING = "backing"
    }
}
