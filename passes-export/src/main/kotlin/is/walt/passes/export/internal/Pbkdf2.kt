package `is`.walt.passes.export.internal

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal object Pbkdf2 {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    internal const val DEFAULT_ITERATIONS = 600_000

    /**
     * Derives a 256-bit key from [passphrase] using PBKDF2-HMAC-SHA256. [passphrase] is
     * cleared after derivation — the caller must not re-use the array. [salt] and
     * [iterations] must match the values stored in [ExportKdfParams.Pbkdf2HmacSha256] so
     * the importer can reproduce the same key.
     */
    internal fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    internal fun generateSalt(): ByteArray =
        ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
}
