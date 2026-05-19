package `is`.walt.passes.storage.internal

import android.content.ContentValues
import android.database.Cursor
import `is`.walt.passes.core.PassInstant
import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.core.ScannableCardCreateInput
import `is`.walt.passes.core.ScannableCardCreateResult
import `is`.walt.passes.core.ScannableCardId
import `is`.walt.passes.core.ScannableCardInputValidator
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.storage.MigrationFailureKind
import `is`.walt.passes.storage.ScannableCardRecordId
import `is`.walt.passes.storage.Schema
import `is`.walt.passes.storage.StorageTelemetryGuard
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * SQLCipher-backed [ScannableCardStore]. Shares the database handle with
 * [SqlCipherPassStore] and [SqlCipherDocumentStore]; the storage repository owns the
 * single handle for the process. Rows surface as fully-materialized [ScannableCard]
 * values — there are no large blob columns to defer, and the consumer's tile renderer
 * needs the payload to re-encode the barcode at render time.
 *
 * Rows whose `format` column does not decode to a known [ScannableFormat] arm, or
 * whose stored fields no longer pass [ScannableCardInputValidator] (defense-in-depth
 * against on-disk tampering), are dropped via
 * [StorageTelemetryGuard.onMigrationRowDropped] and silently omitted from the list.
 */
internal class SqlCipherScannableCardStore(
    private val db: SQLiteDatabase,
    private val telemetryGuard: StorageTelemetryGuard,
) : ScannableCardStore {

    override fun listAll(): List<ScannableCard> {
        val out = ArrayList<ScannableCard>()
        db.rawQuery(
            "SELECT $ALL_COLUMNS FROM ${Schema.Tables.SCANNABLE_CARDS} " +
                "ORDER BY created_at_epoch_ms DESC, id DESC",
            emptyArray(),
        ).use { c ->
            while (c.moveToNext()) {
                val card = c.toCardOrNull() ?: continue
                out += card
            }
        }
        return out
    }

    override fun loadById(id: ScannableCardRecordId): ScannableCard? = db.rawQuery(
        "SELECT $ALL_COLUMNS FROM ${Schema.Tables.SCANNABLE_CARDS} WHERE id = ?",
        arrayOf(id.value.toString()),
    ).use { c -> if (!c.moveToNext()) null else c.toCardOrNull() }

    override fun insert(request: ScannableCardInsertRequest): ScannableCardInsertOutcome {
        val rowId: Long
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("payload", request.payload)
                put("format", request.format.name)
                put("label", request.label)
                put("created_at_epoch_ms", request.nowEpochMs)
            }
            rowId = db.insertOrThrow(Schema.Tables.SCANNABLE_CARDS, null, cv)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ScannableCardInsertOutcome(id = ScannableCardRecordId(rowId))
    }

    override fun delete(id: ScannableCardRecordId): ScannableCardDeleteOutcome? {
        val card = loadById(id) ?: return null
        db.beginTransaction()
        try {
            db.delete(Schema.Tables.SCANNABLE_CARDS, "id = ?", arrayOf(id.value.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ScannableCardDeleteOutcome(format = card.format)
    }

    /**
     * No-op: the SQLCipher handle is owned by [SqlCipherPassStore] for the process
     * lifetime. The interface contract exists so a future store that owns its own
     * resource has an obvious release point.
     */
    override fun close() = Unit

    private fun Cursor.toCardOrNull(): ScannableCard? {
        val idIdx = getColumnIndexOrThrow("id")
        val payloadIdx = getColumnIndexOrThrow("payload")
        val formatIdx = getColumnIndexOrThrow("format")
        val labelIdx = getColumnIndexOrThrow("label")
        val createdIdx = getColumnIndexOrThrow("created_at_epoch_ms")

        val format = ScannableFormat.entries.firstOrNull { it.name == getString(formatIdx) }
            ?: run {
                telemetryGuard.onMigrationRowDropped(MigrationFailureKind.UnknownEnumValue)
                return null
            }
        val rowId = getLong(idIdx)
        val payload = getString(payloadIdx)
        val label = getString(labelIdx)
        val createdAtMs = getLong(createdIdx)

        val result = ScannableCardInputValidator.validate(
            input = ScannableCardCreateInput(
                payload = payload,
                format = format,
                label = label,
            ),
            id = ScannableCardId(rowId.toString()),
            createdAt = PassInstant(createdAtMs),
        )
        return when (result) {
            is ScannableCardCreateResult.Success -> result.card
            is ScannableCardCreateResult.InvalidLabel,
            is ScannableCardCreateResult.InvalidPayload,
            is ScannableCardCreateResult.UnsupportedFormat,
            is ScannableCardCreateResult.EncoderFailure -> {
                telemetryGuard.onMigrationRowDropped(MigrationFailureKind.Other)
                null
            }
        }
    }

    private companion object {
        const val ALL_COLUMNS: String =
            "id, payload, format, label, created_at_epoch_ms"
    }
}
