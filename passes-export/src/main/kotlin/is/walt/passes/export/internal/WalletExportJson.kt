package `is`.walt.passes.export.internal

import kotlinx.serialization.json.Json

/**
 * Shared [Json] instance for encoding and decoding the inner [WalletExportPayload].
 *
 * `ignoreUnknownKeys = true` is critical for forward compatibility: exports produced by
 * a newer build may carry fields this build does not know about, and they must survive
 * a decrypt + re-encrypt round-trip through an older build.
 *
 * `explicitNulls = false` keeps the JSON compact: null fields (e.g. `blob: null`) are
 * omitted rather than written as `"blob": null`. Importers must treat absent and null
 * as equivalent for nullable fields.
 */
internal val WalletExportJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
