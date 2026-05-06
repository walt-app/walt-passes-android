package `is`.walt.passes.storage

/**
 * Defensive caps `passes-storage` re-checks before inserting a document row. The
 * authoritative source is ADR 0005 D7; the renderer-service in `passes-pdf-core` enforces
 * the same numbers at import time. Storage carries the cap a second time so a future
 * caller bug, a misconfigured renderer, or a new entry path cannot land an oversized blob
 * in the encrypted database.
 *
 * Hardcoded here (rather than imported from `passes-pdf-core`) because `passes-storage`
 * does not depend on `passes-pdf-core`: the `PdfDocument <-> documents-table` mapping is a
 * consumer-defined seam.
 */
public object DocumentBounds {
    public const val MAX_BYTES: Long = 25L * 1024 * 1024
    public const val MAX_PAGES: Int = 10
}

/**
 * The list-view projection of a stored PDF document. Mirrors the indexed columns of the
 * `documents` table; the heavy `pdf_bytes` and `document_thumbnails.bytes` blobs are NOT
 * loaded here. Consumers that need the bytes call [PassRepository.loadDocumentBytes] /
 * [PassRepository.loadDocumentThumbnail].
 */
public data class DocumentRow(
    public val id: Long,
    public val displayLabel: String,
    public val byteCount: Long,
    public val pageCount: Int,
    public val importedAtEpochMs: Long,
)
