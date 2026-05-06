package `is`.walt.passes.storage.internal

import `is`.walt.passes.storage.DocumentRow

/**
 * Internal persistence boundary for the `documents` and `document_thumbnails` tables.
 * Same shape as [PassStore]: blocking and synchronous, called by the repository inside an
 * IO dispatcher. The fake test impl lives in `DocumentRepositoryTest`.
 */
internal interface DocumentStore {
    fun listRows(): List<DocumentRow>
    fun insert(request: DocumentInsertRequest): DocumentInsertOutcome
    fun loadBytes(id: Long): ByteArray?
    fun loadThumbnail(id: Long): ByteArray?
    fun delete(id: Long): DocumentDeleteOutcome?
}

internal data class DocumentInsertRequest(
    val displayLabel: String,
    val pdfBytes: ByteArray,
    val byteCount: Long,
    val pageCount: Int,
    val thumbnailBytes: ByteArray,
    val nowEpochMs: Long,
)

internal data class DocumentInsertOutcome(
    val id: Long,
    val row: DocumentRow,
)

internal data class DocumentDeleteOutcome(
    val byteCount: Long,
)
