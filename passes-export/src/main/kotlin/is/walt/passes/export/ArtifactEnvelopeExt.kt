package `is`.walt.passes.export

import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * Builds an [ArtifactEnvelope] from any [ExportableArtifact], with the caller supplying
 * the already-serialised [meta] and optional [blob] bytes.
 *
 * Use this for artifact types whose [toArtifactEnvelope] extension cannot live in
 * `passes-export` due to module boundaries — most notably `StoredPass` from `passes-storage`.
 * The consumer module (e.g. walt-android) serialises the meta into a [JsonObject] and
 * delegates the envelope assembly here.
 *
 * Example (walt-android):
 * ```kotlin
 * storedPass.buildEnvelope(
 *     meta = json.encodeToJsonElement(storedPass.toPassMeta()).jsonObject,
 * )
 * ```
 */
public fun ExportableArtifact.buildEnvelope(
    meta: JsonObject,
    blob: ByteArray? = null,
): ArtifactEnvelope = ArtifactEnvelope(
    kind = exportKind,
    id = exportId,
    createdAt = exportCreatedAt,
    meta = meta,
    blob = blob?.let { Base64.getEncoder().encodeToString(it) },
)
