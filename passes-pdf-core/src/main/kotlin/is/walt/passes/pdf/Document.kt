package `is`.walt.passes.pdf

/**
 * The sealed supertype for every kind of imported, non-signature-verified document Walt
 * stores alongside signed passes. Two arms: [PdfDocument] (wpass-i9x step 2) and
 * [ImageDocument] (step 4). The fields here are exactly what the document surfaces
 * (tile, lane, trust caption) read in common; kind-specific fields — `pageCount` on the
 * PDF arm, `widthPx` / `heightPx` on the image arm — live on their arm, never on the
 * supertype.
 *
 * Like [Pass] in `passes-core`, documents are a *sibling* concept (ADR 0005 D1) and share
 * no superclass with passes. They are not signature-verified (D5); the trust caption is
 * sourced from [provenance], which has a single arm by design.
 *
 * [displayLabel] is supplied at import time by the consumer; the model layer never derives
 * it from document content or metadata, which is part of the no-extraction-from-content
 * discipline (D4). Callers should pass a filename if they have one and a date-based
 * fallback otherwise.
 */
public sealed interface Document {
    public val id: DocumentId
    public val displayLabel: String
    public val byteCount: Long
    public val importedAtEpochMs: Long
    public val provenance: Provenance
}

/**
 * Opaque identifier for a stored [Document]. Each arm wraps its id in a value class so
 * calling code cannot accidentally substitute a `String` from another domain (a pass id,
 * a filename, a user input) into APIs that expect a document id. Sealed so the set of
 * id kinds tracks the set of [Document] arms.
 */
public sealed interface DocumentId {
    public val value: String
}
