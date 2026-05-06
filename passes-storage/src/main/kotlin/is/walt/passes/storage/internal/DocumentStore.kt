package `is`.walt.passes.storage.internal

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

internal data class DocumentInsertRequest(
    val displayLabel: String,
    val pdfBytes: ByteArray,
    val pageCount: Int,
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
