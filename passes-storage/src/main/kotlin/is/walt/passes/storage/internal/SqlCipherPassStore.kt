package `is`.walt.passes.storage.internal

import android.content.ContentValues
import android.database.Cursor
import `is`.walt.passes.core.ImageBytes
import `is`.walt.passes.core.ImageRole
import `is`.walt.passes.core.LocalizedStrings
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassInstant
import `is`.walt.passes.core.PassLocale
import `is`.walt.passes.core.PassType
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.storage.PassRecordId
import `is`.walt.passes.storage.PassSummary
import `is`.walt.passes.storage.Schema
import `is`.walt.passes.storage.StoredPass
import net.sqlcipher.database.SQLiteDatabase

/**
 * SQLCipher-backed implementation of [PassStore]. Owns the `SQLiteDatabase` handle for the
 * lifetime of the `PassRepository`. Concurrency is single-writer: all callers go through the
 * repository's IO dispatcher, which we treat as a serialization point. SQLite WAL mode
 * tolerates concurrent readers, but the repository does not currently exploit that — all
 * reads are also routed through the same dispatcher to keep the StateFlow + telemetry
 * sequencing deterministic (D6).
 */
internal class SqlCipherPassStore(
    private val db: SQLiteDatabase,
) : PassStore {

    override fun listSummaries(): List<PassSummary> {
        val out = ArrayList<PassSummary>()
        db.rawQuery(
            "SELECT id, type, serial_number, organization_name, description, " +
                "expiration_epoch_ms, voided, signature_status_kind, " +
                "created_at_epoch_ms, updated_at_epoch_ms " +
                "FROM ${Schema.Tables.PASSES} ORDER BY created_at_epoch_ms DESC",
            emptyArray(),
        ).use { c ->
            while (c.moveToNext()) {
                out += c.toSummary()
            }
        }
        return out
    }

    override fun loadById(id: PassRecordId): StoredPass? {
        val (summary, blob) = db.rawQuery(
            "SELECT id, type, serial_number, organization_name, description, " +
                "expiration_epoch_ms, voided, signature_status_kind, " +
                "created_at_epoch_ms, updated_at_epoch_ms, pass_json " +
                "FROM ${Schema.Tables.PASSES} WHERE id = ?",
            arrayOf(id.value.toString()),
        ).use { c ->
            if (!c.moveToNext()) return null
            val s = c.toSummary()
            val passJson = c.getBlob(c.getColumnIndexOrThrow("pass_json"))
            s to passJson
        }

        val images = readImages(id)
        val locales = readLocales(id)

        val basePass = PassJsonCodec.decode(blob)
        val passWithChildren = basePass.copy(images = images, locales = locales)
        return StoredPass(
            id = summary.id,
            pass = passWithChildren,
            signatureStatus = summary.signatureStatus,
            createdAt = summary.createdAt,
            updatedAt = summary.updatedAt,
        )
    }

    override fun summaryById(id: PassRecordId): PassSummary? {
        return db.rawQuery(
            "SELECT id, type, serial_number, organization_name, description, " +
                "expiration_epoch_ms, voided, signature_status_kind, " +
                "created_at_epoch_ms, updated_at_epoch_ms " +
                "FROM ${Schema.Tables.PASSES} WHERE id = ?",
            arrayOf(id.value.toString()),
        ).use { c ->
            if (!c.moveToNext()) null else c.toSummary()
        }
    }

    override fun upsert(
        pass: Pass,
        signatureStatus: SignatureStatus,
        nowEpochMs: Long,
    ): UpsertOutcome {
        db.beginTransaction()
        try {
            val existingId: Long? = db.rawQuery(
                "SELECT id, created_at_epoch_ms FROM ${Schema.Tables.PASSES} " +
                    "WHERE type = ? AND serial_number = ? AND organization_name = ?",
                arrayOf(pass.type.name, pass.serialNumber, pass.organizationName),
            ).use { c -> if (c.moveToNext()) c.getLong(0) else null }

            val createdAt: Long = existingId?.let { id ->
                db.rawQuery(
                    "SELECT created_at_epoch_ms FROM ${Schema.Tables.PASSES} WHERE id = ?",
                    arrayOf(id.toString()),
                ).use { c -> if (c.moveToNext()) c.getLong(0) else nowEpochMs }
            } ?: nowEpochMs

            val signatureKind = signatureStatus.kindName()
            val passJson = PassJsonCodec.encode(pass)

            val rowId: Long = if (existingId != null) {
                val cv = ContentValues().apply {
                    put("type", pass.type.name)
                    put("serial_number", pass.serialNumber)
                    put("organization_name", pass.organizationName)
                    put("description", pass.description)
                    pass.expirationDate?.let { put("expiration_epoch_ms", it.epochMillis) }
                        ?: putNull("expiration_epoch_ms")
                    put("voided", if (pass.voided) 1 else 0)
                    put("signature_status_kind", signatureKind)
                    put("pass_json", passJson)
                    put("updated_at_epoch_ms", nowEpochMs)
                }
                db.update(
                    Schema.Tables.PASSES,
                    cv,
                    "id = ?",
                    arrayOf(existingId.toString()),
                )
                db.delete(Schema.Tables.PASS_IMAGES, "pass_id = ?", arrayOf(existingId.toString()))
                db.delete(Schema.Tables.PASS_LOCALES, "pass_id = ?", arrayOf(existingId.toString()))
                existingId
            } else {
                val cv = ContentValues().apply {
                    put("type", pass.type.name)
                    put("serial_number", pass.serialNumber)
                    put("organization_name", pass.organizationName)
                    put("description", pass.description)
                    pass.expirationDate?.let { put("expiration_epoch_ms", it.epochMillis) }
                        ?: putNull("expiration_epoch_ms")
                    put("voided", if (pass.voided) 1 else 0)
                    put("signature_status_kind", signatureKind)
                    put("pass_json", passJson)
                    put("created_at_epoch_ms", createdAt)
                    put("updated_at_epoch_ms", nowEpochMs)
                }
                db.insertOrThrow(Schema.Tables.PASSES, null, cv)
            }

            for ((role, bytes) in pass.images) {
                val cv = ContentValues().apply {
                    put("pass_id", rowId)
                    put("role", role.name)
                    put("bytes", bytes.bytes)
                }
                db.insertOrThrow(Schema.Tables.PASS_IMAGES, null, cv)
            }
            for ((locale, strings) in pass.locales) {
                val cv = ContentValues().apply {
                    put("pass_id", rowId)
                    put("locale_tag", locale.tag)
                    put("strings_json", PassJsonCodec.encodeStrings(strings))
                }
                db.insertOrThrow(Schema.Tables.PASS_LOCALES, null, cv)
            }

            db.setTransactionSuccessful()

            val summary = PassSummary(
                id = PassRecordId(rowId),
                type = pass.type,
                serialNumber = pass.serialNumber,
                organizationName = pass.organizationName,
                description = pass.description,
                expirationDate = pass.expirationDate,
                voided = pass.voided,
                signatureStatus = signatureStatus,
                createdAt = PassInstant(createdAt),
                updatedAt = PassInstant(nowEpochMs),
            )
            return UpsertOutcome(
                recordId = PassRecordId(rowId),
                summary = summary,
                wasReplacement = existingId != null,
            )
        } finally {
            db.endTransaction()
        }
    }

    override fun delete(id: PassRecordId): DeleteOutcome? {
        val summary = summaryById(id) ?: return null
        db.beginTransaction()
        try {
            // ON DELETE CASCADE on pass_images and pass_locales handles the children.
            db.delete(Schema.Tables.PASSES, "id = ?", arrayOf(id.value.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return DeleteOutcome(summary)
    }

    override fun close() {
        db.close()
    }

    private fun readImages(id: PassRecordId): Map<ImageRole, ImageBytes> {
        val out = HashMap<ImageRole, ImageBytes>()
        db.rawQuery(
            "SELECT role, bytes FROM ${Schema.Tables.PASS_IMAGES} WHERE pass_id = ?",
            arrayOf(id.value.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val role = ImageRole.valueOf(c.getString(0))
                out[role] = ImageBytes(c.getBlob(1))
            }
        }
        return out
    }

    private fun readLocales(id: PassRecordId): Map<PassLocale, LocalizedStrings> {
        val out = HashMap<PassLocale, LocalizedStrings>()
        db.rawQuery(
            "SELECT locale_tag, strings_json FROM ${Schema.Tables.PASS_LOCALES} WHERE pass_id = ?",
            arrayOf(id.value.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val tag = c.getString(0)
                val bytes = c.getBlob(1)
                out[PassLocale(tag)] = PassJsonCodec.decodeStrings(bytes)
            }
        }
        return out
    }

    private fun Cursor.toSummary(): PassSummary = PassSummary(
        id = PassRecordId(getLong(getColumnIndexOrThrow("id"))),
        type = PassType.valueOf(getString(getColumnIndexOrThrow("type"))),
        serialNumber = getString(getColumnIndexOrThrow("serial_number")),
        organizationName = getString(getColumnIndexOrThrow("organization_name")),
        description = getString(getColumnIndexOrThrow("description")),
        expirationDate = getColumnIndexOrThrow("expiration_epoch_ms").let { idx ->
            if (isNull(idx)) null else PassInstant(getLong(idx))
        },
        voided = getInt(getColumnIndexOrThrow("voided")) != 0,
        signatureStatus = signatureStatusFromKind(getString(getColumnIndexOrThrow("signature_status_kind"))),
        createdAt = PassInstant(getLong(getColumnIndexOrThrow("created_at_epoch_ms"))),
        updatedAt = PassInstant(getLong(getColumnIndexOrThrow("updated_at_epoch_ms"))),
    )

    private fun signatureStatusFromKind(name: String): SignatureStatus = when (name) {
        "Unsigned" -> SignatureStatus.Unsigned
        "SelfSigned" -> SignatureStatus.SelfSigned
        "AppleVerified" -> SignatureStatus.AppleVerified
        "CertChainIncomplete" -> SignatureStatus.CertChainIncomplete
        else -> error("unknown signature_status_kind '$name'")
    }

    private fun SignatureStatus.kindName(): String = when (this) {
        SignatureStatus.Unsigned -> "Unsigned"
        SignatureStatus.SelfSigned -> "SelfSigned"
        SignatureStatus.AppleVerified -> "AppleVerified"
        SignatureStatus.CertChainIncomplete -> "CertChainIncomplete"
    }
}
