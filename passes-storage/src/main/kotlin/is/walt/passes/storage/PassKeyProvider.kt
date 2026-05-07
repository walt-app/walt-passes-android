package `is`.walt.passes.storage

/**
 * Source of the 32-byte raw key handed to SQLCipher's `PRAGMA key`. The Android-only
 * implementation wraps a randomly generated key with a non-exportable Keystore master
 * key (see ADR 0002 D2); the JVM-test implementation supplies a fixed key for round-trip
 * tests of the schema.
 *
 * The Android implementation is produced by `AndroidKeystorePassKeyProvider.create(...)`,
 * which is the only place that touches `android.security.keystore.*` types. Keeping that
 * dependency off the public interface lets the contract be exercised on JVM CI.
 */
public interface PassKeyProvider {
    /**
     * Returns the 32-byte raw database key. Implementations MUST zero out any local
     * buffers holding the key bytes after returning; SQLCipher takes ownership of the
     * returned array internally.
     *
     * Returns [StorageError.KeyUnavailable] if the master alias is gone; returns
     * [StorageError.KeyUnwrapFailed] if the wrapped blob exists but cannot be unwrapped.
     */
    public fun provideDatabaseKey(): StorageResult<DatabaseKey>

    /**
     * Reports which Keystore backing was actually selected for the master key. Surfaced
     * via [StorageTelemetryGuard.onKeyProviderInitialized]; useful in the wallet UI when
     * the user wants to verify hardware-backing on their device.
     */
    public val keyBacking: KeyBacking
}

/**
 * Wrapper around the raw 32-byte database key. Exists to make accidental logging
 * discouragingly verbose: the class deliberately overrides `toString()` to a redacted
 * form, and exposes byte access only via [withBytes] (synchronous borrow) or
 * [retainAcross] (lifetime-tied borrow).
 */
public class DatabaseKey(private val bytes: ByteArray) {
    init {
        require(bytes.size == 32) { "DatabaseKey must be exactly 32 bytes" }
    }

    /**
     * Hands the raw key bytes to [block] and zeros the internal buffer when [block]
     * returns. Callers MUST NOT retain the [ByteArray] beyond the block.
     */
    public fun <R> withBytes(block: (ByteArray) -> R): R {
        try {
            return block(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Hands the raw key bytes to [block] for capture by a long-lived consumer that
     * retains the [ByteArray] reference past the synchronous call. Returns an
     * [AutoCloseable] that zeros the buffer when closed; the caller MUST close it
     * once the consumer no longer needs the bytes.
     *
     * Required by `net.zetetic.database.sqlcipher`: `SQLiteDatabaseConfiguration`
     * holds the password byte[] by reference (no copy), and `SQLiteConnection.open()`
     * re-reads `mConfiguration.password` on every pool connection it opens, including
     * read-only connections opened lazily on first cursor read. Zeroing the buffer
     * before the connection pool is done with it re-keys new pool connections with
     * all zeros, surfacing as `SQLiteOutOfMemoryException` from page-1 decrypt.
     */
    public fun retainAcross(block: (ByteArray) -> Unit): AutoCloseable {
        block(bytes)
        return AutoCloseable { bytes.fill(0) }
    }

    override fun toString(): String = "DatabaseKey(redacted)"
}

/**
 * Reports which Android Keystore backing was used for the master key that wraps the DB
 * key. The wallet UI surfaces this so users can verify hardware-backing on their device.
 *
 * `Software` is reachable on emulators and on devices whose Keystore implementation
 * declined to provide a hardware-backed key; the library does NOT refuse to operate in
 * this case, because doing so would brick the wallet on emulator-based development.
 */
public enum class KeyBacking {
    StrongBox,
    Tee,
    Software,
}
