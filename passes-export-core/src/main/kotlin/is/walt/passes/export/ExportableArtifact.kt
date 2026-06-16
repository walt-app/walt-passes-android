package `is`.walt.passes.export

/**
 * Marks a domain object as a first-class wallet artifact that participates in export and
 * import. Implementing this interface is the only step required to make a new artifact type
 * visible to [WalletExporter] — no mapper registration, no factory entry.
 *
 * The three properties supply the fields that every [ArtifactEnvelope] must carry regardless
 * of artifact type. The structured payload specific to this type (labels, format, page count,
 * etc.) is expected to be contributed via [kotlinx.serialization] — the exporter will
 * serialise the implementing object directly into [ArtifactEnvelope.meta].
 *
 * [exportKind] must be one of the constants in [ArtifactKind] for known types. Unknown kinds
 * encountered at import time are preserved verbatim, so future artifact types survive a
 * round-trip through an older build.
 *
 * [exportCreatedAt] must be a UTC ISO-8601 instant string, e.g. `"2026-06-15T15:06:40Z"`.
 * Use `java.time.Instant.ofEpochMilli(epochMs).toString()` for consistency with [WalletExporter].
 *
 * Note on [Pass]: [Pass] does not implement this interface because its storage id and creation
 * timestamp live in the storage layer, not in the domain object itself. A storage-layer wrapper
 * supplies those fields at export time instead.
 */
public interface ExportableArtifact {
    public val exportKind: String
    public val exportId: String
    public val exportCreatedAt: String
}
