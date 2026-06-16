package `is`.walt.passes.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Structured metadata stored in [ArtifactEnvelope.meta] for [ArtifactKind.PKPASS].
 * Constructed by the consumer from `StoredPass` (in `passes-storage`), since `passes-export`
 * does not depend on `passes-storage`.
 *
 * [ArtifactEnvelope.blob] is always `null` for this artifact kind: `passes-storage` stores
 * the parsed `pass_json`, not the original `.pkpass` archive bytes. A future schema change
 * could add raw-byte retention to enable full re-verification on import.
 *
 * **Security note on [signatureStatus]:** this field reflects the verification result at
 * the time of the original import. An importer MUST NOT treat it as a live trust assertion —
 * the archive bytes are not present to re-verify.
 */
@Serializable
public data class PassMeta(
    @SerialName("type") public val type: String,
    @SerialName("serial_number") public val serialNumber: String,
    @SerialName("description") public val description: String,
    @SerialName("organization_name") public val organizationName: String,
    @SerialName("expiration_date") public val expirationDate: String?,
    @SerialName("voided") public val voided: Boolean,
    @SerialName("signature_status") public val signatureStatus: String,
)
