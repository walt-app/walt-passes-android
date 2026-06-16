package `is`.walt.passes.export

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The top-level structure of a `.walt` export file. The outer envelope is intentionally
 * readable without the decryption key: the [WalletExportEnvelope.kdf] and
 * [WalletExportEnvelope.encryption] sections tell the importer which credential to
 * request from the user before attempting decryption.
 *
 * The file is a single JSON document — not a ZIP. Binary artifacts (PDFs, `.pkpass` bytes)
 * are embedded as base64 inside the encrypted [WalletExportEnvelope.ciphertext]. This
 * makes the export a single portable unit that can be stored in a password manager,
 * sent via AirDrop, or emailed, without the receiver needing to handle a multi-file archive.
 *
 * File extension: [ExportConstants.FILE_EXTENSION] (`.walt`).
 * MIME type: [ExportConstants.MIME_TYPE].
 */
@Serializable
public data class WalletExportFile(
    @SerialName("walt") public val walt: WalletExportEnvelope,
)

/**
 * The readable outer envelope. [kdf] and [encryption] are always in the clear so the
 * importer can reconstruct the decryption key before touching [ciphertext].
 *
 * [ciphertext] is the base64-encoded output of AES-256-GCM `doFinal` on the UTF-8
 * bytes of the serialised [WalletExportPayload] JSON. The 16-byte GCM authentication
 * tag is appended to the ciphertext by the Java `Cipher` API; it is not stored separately.
 * Successful decryption proves both confidentiality and integrity — no separate HMAC needed.
 */
@Serializable
public data class WalletExportEnvelope(
    @SerialName("format") public val format: String,
    @SerialName("version") public val version: Int,
    @SerialName("kdf") public val kdf: ExportKdfParams,
    @SerialName("encryption") public val encryption: ExportEncryptionParams,
    @SerialName("ciphertext") public val ciphertext: String,
)

/**
 * Key-derivation parameters. The [algorithm] field is the JSON discriminator.
 *
 *  - [Direct]: the key from the credential manager is used without derivation.
 *    Primary path for Android Credential Manager / iCloud Keychain flows.
 *  - [Pbkdf2HmacSha256]: a user-typed passphrase is stretched before use.
 *    Fallback for contexts where a credential manager is unavailable.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("algorithm")
public sealed interface ExportKdfParams {
    @Serializable
    @SerialName("direct")
    public data object Direct : ExportKdfParams

    @Serializable
    @SerialName("pbkdf2-hmac-sha256")
    public data class Pbkdf2HmacSha256(
        @SerialName("salt") public val salt: String,
        @SerialName("iterations") public val iterations: Int,
    ) : ExportKdfParams
}

/**
 * Symmetric cipher parameters. [nonce] is the base64-encoded 12-byte GCM IV; a fresh
 * nonce is generated for each export. [algorithm] is included explicitly so future
 * cipher additions are detectable by older importers.
 */
@Serializable
public data class ExportEncryptionParams(
    @SerialName("algorithm") public val algorithm: String,
    @SerialName("nonce") public val nonce: String,
)

/** String constants for the format identification and algorithm fields. */
public object ExportConstants {
    public const val FORMAT: String = "wallet-export"
    public const val VERSION: Int = 1
    public const val ALGORITHM_AES_256_GCM: String = "AES-256-GCM"
    public const val FILE_EXTENSION: String = ".walt"
    public const val MIME_TYPE: String = "application/vnd.walt.wallet-export+json"
}
