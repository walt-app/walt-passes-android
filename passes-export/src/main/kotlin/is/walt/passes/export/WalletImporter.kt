package `is`.walt.passes.export

import `is`.walt.passes.export.internal.AesGcm
import `is`.walt.passes.export.internal.Pbkdf2
import `is`.walt.passes.export.internal.WalletExportJson
import kotlinx.serialization.decodeFromString
import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * Decrypts a [WalletExportFile] back into a [WalletExportPayload]. Mirrors [WalletExporter]
 * with one method per key-supply mode. All failures are returned as [WalletImportError]
 * inside a [Result] — no try/catch at the call site.
 *
 * The JSON decoder uses `ignoreUnknownKeys = true` so exports from newer builds survive
 * decryption by older builds: unknown fields in [WalletExportPayload] are silently skipped
 * rather than causing a parse failure.
 *
 * [parseFile] is the entry point for reading the outer envelope from a file on disk; call
 * it before [decrypt] or [decryptWithPassphrase].
 */
public class WalletImporter {

    /**
     * Deserialises the raw JSON content of a `.walt` file into a [WalletExportFile].
     * Returns [WalletImportError.MalformedFile] if the JSON cannot be parsed.
     */
    public fun parseFile(content: String): Result<WalletExportFile> =
        runCatching {
            try {
                WalletExportJson.decodeFromString<WalletExportFile>(content)
            } catch (e: Exception) {
                throw WalletImportError.MalformedFile(e)
            }
        }

    /**
     * Decrypts [file] with the raw [key] from Credential Manager / iCloud Keychain.
     * [file.walt.kdf] must be [ExportKdfParams.Direct]; returns [WalletImportError.WrongKdf]
     * if it is not. Returns [WalletImportError.AuthenticationFailed] if the key is wrong
     * or the file was tampered.
     */
    public fun decrypt(file: WalletExportFile, key: ByteArray): Result<WalletExportPayload> =
        runCatching {
            if (file.walt.kdf !is ExportKdfParams.Direct) {
                throw WalletImportError.WrongKdf(
                    expected = "direct",
                    actual = file.walt.kdf.algorithmName(),
                )
            }
            decryptInner(file, key)
        }

    /**
     * Decrypts [file] with a key derived from [passphrase] via PBKDF2-HMAC-SHA256.
     * [file.walt.kdf] must be [ExportKdfParams.Pbkdf2HmacSha256]; returns
     * [WalletImportError.WrongKdf] if it is not. [passphrase] is zeroed after key
     * derivation; do not reuse the array.
     */
    public fun decryptWithPassphrase(
        file: WalletExportFile,
        passphrase: CharArray,
    ): Result<WalletExportPayload> =
        runCatching {
            val kdf = file.walt.kdf as? ExportKdfParams.Pbkdf2HmacSha256
                ?: throw WalletImportError.WrongKdf(
                    expected = "pbkdf2-hmac-sha256",
                    actual = file.walt.kdf.algorithmName(),
                )
            val salt = Base64.getDecoder().decode(kdf.salt)
            val key = Pbkdf2.deriveKey(passphrase, salt, kdf.iterations)
            passphrase.fill(' ')
            decryptInner(file, key)
        }

    private fun decryptInner(file: WalletExportFile, key: ByteArray): WalletExportPayload {
        if (file.walt.version > ExportConstants.VERSION) {
            throw WalletImportError.UnsupportedVersion(found = file.walt.version)
        }
        val nonce = Base64.getDecoder().decode(file.walt.encryption.nonce)
        val ciphertext = Base64.getDecoder().decode(file.walt.ciphertext)
        val plaintext = try {
            AesGcm.decrypt(ciphertext, key, nonce)
        } catch (e: AEADBadTagException) {
            throw WalletImportError.AuthenticationFailed
        }
        return WalletExportJson.decodeFromString(plaintext.decodeToString())
    }

    private fun ExportKdfParams.algorithmName(): String = when (this) {
        is ExportKdfParams.Direct -> "direct"
        is ExportKdfParams.Pbkdf2HmacSha256 -> "pbkdf2-hmac-sha256"
    }
}
