package `is`.walt.passes.storage.internal

import `is`.walt.passes.storage.DocumentFormat
import `is`.walt.passes.storage.DocumentRecordId
import `is`.walt.passes.storage.DocumentRow

/**
 * Internal persistence boundary for the `documents` and `document_thumbnails` tables.
 * Same shape as [PassStore]: blocking and synchronous, called by the repository inside
 * an IO dispatcher. The fake test impl lives in `DocumentRepositoryTest`.
 */
internal interface DocumentStore {
    fun listRows(): List<DocumentRow>
    fun insert(request: DocumentInsertRequest): DocumentInsertOutcome
    fun loadBytes(id: DocumentRecordId): ByteArray?
    fun loadThumbnail(id: DocumentRecordId): ByteArray?

    /**
     * Single-column UPDATE on the documents row. Returns `true` if a row matched [id],
     * `false` otherwise (caller maps to `IntegrityViolation`).
     */
    fun updateLabel(id: DocumentRecordId, label: String): Boolean
    fun delete(id: DocumentRecordId): DocumentDeleteOutcome?

    /**
     * Releases any resources owned exclusively by the store. The current production
     * impl does not own a separate handle (it shares the SQLCipher database with
     * [SqlCipherPassStore] and that store's `close()` releases the handle), so its
     * implementation is a no-op. The method exists for forward-compatibility with a
     * future store that holds its own prepared-statement cache or tracked cursor.
     */
    fun close()
}

/**
 * A resolved insert for the `documents` table, kind-discriminated by [format]. The
 * repository flattens the public [DocumentInsert][`is`.walt.passes.storage.DocumentInsert]
 * sealed arms into this single row shape: [pageCount] carries the PDF page count (or `1` for
 * an image, a single page), and [widthPx] / [heightPx] carry the image dimensions (or `null`
 * for a PDF). [bytes] is the original document bytes; the store writes them to the reused
 * `pdf_bytes` BLOB column verbatim.
 */
internal data class DocumentInsertRequest(
    val displayLabel: String,
    val bytes: ByteArray,
    val format: DocumentFormat,
    val pageCount: Int,
    val widthPx: Int?,
    val heightPx: Int?,
    val thumbnailBytes: ByteArray,
    val nowEpochMs: Long,
)

internal data class DocumentInsertOutcome(
    val id: DocumentRecordId,
    val row: DocumentRow,
)

internal data class DocumentDeleteOutcome(
    val byteCount: Long,
)
