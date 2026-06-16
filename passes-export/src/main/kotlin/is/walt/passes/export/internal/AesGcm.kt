package `is`.walt.passes.export.internal

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object AesGcm {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    /**
     * Encrypts [plaintext] with [key] using AES-256-GCM. Generates a fresh random nonce
     * for each call. Returns the nonce and the `ciphertext || 16-byte GCM tag` pair
     * produced by Java's `Cipher.doFinal`.
     */
    internal fun encrypt(plaintext: ByteArray, key: ByteArray): EncryptResult {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return EncryptResult(ciphertext = cipher.doFinal(plaintext), nonce = nonce)
    }

    /**
     * Decrypts [ciphertext] (ciphertext bytes with the 16-byte GCM tag appended) using
     * [key] and [nonce]. Throws [javax.crypto.AEADBadTagException] if the authentication
     * tag does not verify — this signals either a wrong key or a tampered file.
     */
    internal fun decrypt(ciphertext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    internal data class EncryptResult(val ciphertext: ByteArray, val nonce: ByteArray) {
        override fun equals(other: Any?) =
            other is EncryptResult &&
                ciphertext.contentEquals(other.ciphertext) &&
                nonce.contentEquals(other.nonce)

        override fun hashCode() =
            31 * ciphertext.contentHashCode() + nonce.contentHashCode()
    }
}
