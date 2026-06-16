package `is`.walt.passes.export

import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.export.internal.WalletExportJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Structured metadata stored in [ArtifactEnvelope.meta] for [ArtifactKind.SCANNABLE_CARD]. */
@Serializable
public data class ScannableCardMeta(
    @SerialName("label") public val label: String,
    @SerialName("format") public val format: String,
    @SerialName("payload") public val payload: String,
)

public fun ScannableCard.toArtifactEnvelope(): ArtifactEnvelope = ArtifactEnvelope(
    kind = exportKind,
    id = exportId,
    createdAt = exportCreatedAt,
    meta = WalletExportJson.encodeToJsonElement(
        ScannableCardMeta(label = label, format = format.name, payload = payload),
    ).jsonObject,
    blob = null,
)
