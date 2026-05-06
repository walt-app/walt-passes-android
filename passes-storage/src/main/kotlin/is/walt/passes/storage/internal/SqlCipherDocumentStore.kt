package `is`.walt.passes.storage.internal

import android.content.ContentValues
import `is`.walt.passes.storage.DocumentRecordId
import `is`.walt.passes.storage.DocumentRow
import `is`.walt.passes.storage.Schema
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * SQLCipher-backed [DocumentStore]. Shares the database handle with [SqlCipherPassStore];
 * the `passes-storage` repository owns the single handle for the process. PDF and
 * thumbnail bytes round-trip as opaque BLOBs; this class never decodes them.
 *
 * `byteCount` is derived from `pdfBytes.size` rather than caller-asserted, so a stale
 * size header from a future caller cannot bypass [is.walt.passes.storage.DocumentBounds]
 * checks at the repository layer.
 */
internal class SqlCipherDocumentStore(
    private val db: SQLiteDatabase,
) : DocumentStore {

    override fun listRows(): List<DocumentRow> {
        val out = ArrayList<DocumentRow>()
        db.rawQuery(
            "SELECT id, display_label, byte_count, page_count, imported_at_epoch_ms " +
                "FROM ${Schema.Tables.DOCUMENTS} ORDER BY imported_at_epoch_ms DESC, id DESC",
            emptyArray(),
        ).use { c ->
            while (c.moveToNext()) {
                out += DocumentRow(
                    id = DocumentRecordId(c.getLong(0)),
                    displayLabel = c.getString(1),
                    byteCount = c.getLong(2),
                    pageCount = c.getInt(3),
                    importedAtEpochMs = c.getLong(4),
                )
            }
        }
        return out
    }

    override fun insert(request: DocumentInsertRequest): DocumentInsertOutcome {
        val byteCount = request.pdfBytes.size.toLong()
        db.beginTransaction()
        try {
            val docCv = ContentValues().apply {
                put("display_label", request.displayLabel)
                put("pdf_bytes", request.pdfBytes)
                put("byte_count", byteCount)
                put("page_count", request.pageCount)
                put("imported_at_epoch_ms", request.nowEpochMs)
            }
            val rowId = db.insertOrThrow(Schema.Tables.DOCUMENTS, null, docCv)
            val thumbCv = ContentValues().apply {
                put("document_id", rowId)
                put("bytes", request.thumbnailBytes)
            }
            db.insertOrThrow(Schema.Tables.DOCUMENT_THUMBNAILS, null, thumbCv)
            db.setTransactionSuccessful()
            val recordId = DocumentRecordId(rowId)
            return DocumentInsertOutcome(
                id = recordId,
                row = DocumentRow(
                    id = recordId,
                    displayLabel = request.displayLabel,
                    byteCount = byteCount,
                    pageCount = request.pageCount,
                    importedAtEpochMs = request.nowEpochMs,
                ),
            )
        } finally {
            db.endTransaction()
        }
    }

    override fun loadBytes(id: DocumentRecordId): ByteArray? = db.rawQuery(
        "SELECT pdf_bytes FROM ${Schema.Tables.DOCUMENTS} WHERE id = ?",
        arrayOf(id.value.toString()),
    ).use { c -> if (c.moveToNext()) c.getBlob(0) else null }

    override fun loadThumbnail(id: DocumentRecordId): ByteArray? = db.rawQuery(
        "SELECT bytes FROM ${Schema.Tables.DOCUMENT_THUMBNAILS} WHERE document_id = ?",
        arrayOf(id.value.toString()),
    ).use { c -> if (c.moveToNext()) c.getBlob(0) else null }

    override fun delete(id: DocumentRecordId): DocumentDeleteOutcome? {
        val byteCount: Long = db.rawQuery(
            "SELECT byte_count FROM ${Schema.Tables.DOCUMENTS} WHERE id = ?",
            arrayOf(id.value.toString()),
        ).use { c -> if (c.moveToNext()) c.getLong(0) else return null }

        db.beginTransaction()
        try {
            // ON DELETE CASCADE drops the thumbnail row.
            db.delete(Schema.Tables.DOCUMENTS, "id = ?", arrayOf(id.value.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return DocumentDeleteOutcome(byteCount = byteCount)
    }

    /**
     * No-op: the SQLCipher handle is owned by [SqlCipherPassStore] for the process
     * lifetime. The interface contract exists so a future store that owns its own
     * resource has an obvious release point.
     */
    override fun close() = Unit
}
