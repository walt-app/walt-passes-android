package `is`.walt.passes.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Universal container for a single wallet artifact in a [WalletExportPayload].
 *
 * [kind] is the stable wire name from [ArtifactKind]. [meta] carries kind-specific
 * structured metadata as a JSON object; its schema is defined per kind by the mapper
 * that produced it (e.g. `PassArtifactMapper`, `ScannableCardArtifactMapper`). [blob]
 * carries raw binary data — the original `.pkpass` ZIP or PDF bytes — as a standard
 * base64 string; it is `null` for artifact types that have no binary source or where
 * the source bytes are not available.
 *
 * [createdAt] is an ISO-8601 UTC instant (e.g. `"2026-06-15T12:00:00Z"`). The string
 * form is chosen over epoch millis for human readability when the decrypted JSON is
 * inspected manually, and for native compatibility with Swift's `ISO8601DateFormatter`.
 *
 * **Extensibility contract**: an importer that encounters an unknown [kind] MUST
 * preserve the whole envelope verbatim on round-trip. Never fail on an unknown kind —
 * the user's future-artifact data must survive an import into an older build.
 */
@Serializable
public data class ArtifactEnvelope(
    @SerialName("kind") public val kind: String,
    @SerialName("id") public val id: String,
    @SerialName("created_at") public val createdAt: String,
    @SerialName("meta") public val meta: JsonObject,
    @SerialName("blob") public val blob: String?,
)
