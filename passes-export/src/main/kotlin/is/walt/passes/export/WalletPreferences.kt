package `is`.walt.passes.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * User-level preferences preserved across export/import. Typed fields here cover
 * preferences known at compile time; [extensions] is the escape hatch for future keys
 * that do not need a typed field yet.
 *
 * Importers MUST preserve unknown keys in [extensions] on round-trip so future
 * preference additions survive a restore into an older build.
 *
 * [sortOrder] is the ordered list of artifact IDs representing the user's home-screen
 * arrangement. Artifacts absent from this list are appended at the end in
 * creation-date order on import.
 */
@Serializable
public data class WalletPreferences(
    @SerialName("sort_order") public val sortOrder: List<String> = emptyList(),
    @SerialName("extensions") public val extensions: JsonObject = JsonObject(emptyMap()),
)
