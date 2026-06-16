package `is`.walt.passes.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The inner wallet payload — the plaintext produced after decrypting a [WalletExportFile].
 *
 * [schemaVersion] governs the shape of [artifacts] and [preferences]. [CURRENT_SCHEMA_VERSION]
 * is what this build produces and understands. Importers SHOULD warn — not fail — on a
 * [schemaVersion] higher than [CURRENT_SCHEMA_VERSION]; they may have enough structural
 * compatibility to restore what they recognise.
 *
 * [exportedAt] is an ISO-8601 UTC instant stamped by [WalletExporter] at encryption time;
 * callers do not supply it.
 *
 * [platform] is informational ("android", "ios"). Importers MUST NOT gate behaviour on it;
 * a wallet exported from Android must import cleanly on iOS and vice versa.
 *
 * [extensions] is the top-level escape hatch for future fields that do not fit [preferences].
 * Importers MUST preserve unknown keys in [extensions] on round-trip.
 */
@Serializable
public data class WalletExportPayload(
    @SerialName("schema_version") public val schemaVersion: Int,
    @SerialName("exported_at") public val exportedAt: String,
    @SerialName("platform") public val platform: String,
    @SerialName("artifacts") public val artifacts: List<ArtifactEnvelope>,
    @SerialName("preferences") public val preferences: WalletPreferences,
    @SerialName("extensions") public val extensions: JsonObject,
) {
    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
