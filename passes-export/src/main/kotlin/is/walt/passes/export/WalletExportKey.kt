package `is`.walt.passes.export

import java.security.SecureRandom
import java.util.Base64

/**
 * Utilities for the 256-bit symmetric key that encrypts a [WalletExportFile].
 *
 * The key is a random 32-byte array with no internal structure. It is base64-encoded for
 * storage as a "password" entry in a credential manager (Android Credential Manager,
 * iCloud Keychain, or any third-party provider such as 1Password or Bitwarden). The
 * credential manager entry is the user's property; Walt never retains the key.
 *
 * Encoding uses the standard (non-URL-safe) base64 alphabet with no line breaks, which
 * is what password managers treat as a printable password field.
 */
public object WalletExportKey {
    /** Length of a valid key in bytes. */
    public const val KEY_SIZE_BYTES: Int = 32

    /** Generates a fresh cryptographically-random 256-bit key. */
    public fun generate(): ByteArray {
        val key = ByteArray(KEY_SIZE_BYTES)
        SecureRandom().nextBytes(key)
        return key
    }

    /** Encodes [key] to a base64 string suitable for storage in a credential manager. */
    public fun encode(key: ByteArray): String =
        Base64.getEncoder().encodeToString(key)

    /**
     * Decodes a base64 string previously produced by [encode] back to raw key bytes.
     *
     * @throws IllegalArgumentException if [encoded] is not valid base64 or decodes to a
     *   length other than [KEY_SIZE_BYTES].
     */
    public fun decode(encoded: String): ByteArray {
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Key is not valid base64", e)
        }
        require(bytes.size == KEY_SIZE_BYTES) {
            "Expected $KEY_SIZE_BYTES bytes after base64 decode but got ${bytes.size}"
        }
        return bytes
    }
}
