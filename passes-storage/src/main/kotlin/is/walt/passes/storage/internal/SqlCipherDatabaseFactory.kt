package `is`.walt.passes.storage.internal

import android.content.Context
import `is`.walt.passes.storage.DatabaseKey
import `is`.walt.passes.storage.Schema
import `is`.walt.passes.storage.StorageError
import `is`.walt.passes.storage.StorageResult
import `is`.walt.passes.storage.UnknownStorageFailureKind
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Opens (or creates on first use) the `walt_passes.db` SQLCipher file with the supplied
 * [DatabaseKey], applies the SQLCipher v4 compatibility pragma per ADR 0002, runs
 * [Schema.DDL] under a single transaction on first use, and enables `PRAGMA foreign_keys=ON`
 * so the `ON DELETE CASCADE` chains for `pass_images` and `pass_locales` actually fire
 * (SQLite/SQLCipher default this off).
 *
 * The native libraries are loaded lazily via `System.loadLibrary("sqlcipher")`, which is
 * idempotent on subsequent calls.
 */
internal object SqlCipherDatabaseFactory {
    @Volatile
    private var librariesLoaded: Boolean = false

    private fun loadLibsOnce() {
        if (!librariesLoaded) {
            synchronized(this) {
                if (!librariesLoaded) {
                    System.loadLibrary("sqlcipher")
                    librariesLoaded = true
                }
            }
        }
    }

    fun openOrCreate(
        context: Context,
        databaseKey: DatabaseKey,
    ): StorageResult<SQLiteDatabase> {
        loadLibsOnce()
        val dbFile: File = context.applicationContext.getDatabasePath(Schema.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()

        val db: SQLiteDatabase = databaseKey.withBytes { rawKey ->
            // SQLCipher takes the 32 bytes directly via the byte[] password overload; the
            // withBytes scope zeros our local copy on return. SQLCipher's internal page-key
            // derivation buffer survives for the lifetime of the open connection, which is
            // bounded by SqlCipherPassStore.close().
            SQLiteDatabase.openOrCreateDatabase(dbFile, rawKey, null, null)
        }

        // ADR 0002: pin SQLCipher v4 page format. Defensive against a future SQLCipher
        // major-version default change; v4 is the format the wallet's on-disk DBs will be
        // in for the foreseeable future. Issued before any other DDL/DML so the page format
        // is locked before tables get created.
        db.execSQL("PRAGMA cipher_compatibility = 4")
        db.execSQL("PRAGMA foreign_keys = ON")

        // Detect schema version on disk before mutating anything. ADR D7: a build that
        // sees a newer-than-it-understands schema must surface Unsupported, not silently
        // run its (older) DDL.
        val onDiskVersion: Int? = readSchemaVersionIfPresent(db)
        if (onDiskVersion != null && onDiskVersion > Schema.VERSION) {
            db.close()
            return StorageResult.Failure(StorageError.Unsupported(onDiskVersion))
        }

        db.beginTransaction()
        try {
            for (statement in Schema.DDL) {
                db.execSQL(statement)
            }
            db.execSQL(
                "INSERT OR REPLACE INTO ${Schema.Tables.SCHEMA_META} (key, value) VALUES (?, ?)",
                arrayOf<Any>(Schema.MetaKeys.SCHEMA_VERSION, Schema.VERSION.toString().toByteArray()),
            )
            db.setTransactionSuccessful()
        } catch (e: Throwable) {
            db.endTransaction()
            db.close()
            return StorageResult.Failure(
                StorageError.Unknown(
                    kind = UnknownStorageFailureKind.DatabaseCorrupt,
                    cause = e,
                ),
            )
        }
        db.endTransaction()
        return StorageResult.Success(db)
    }

    private fun readSchemaVersionIfPresent(db: SQLiteDatabase): Int? {
        // schema_meta itself may not exist yet on first open; tolerate that and let the
        // DDL block create it.
        val tableExists = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(Schema.Tables.SCHEMA_META),
        ).use { it.moveToNext() }
        if (!tableExists) return null

        return db.rawQuery(
            "SELECT value FROM ${Schema.Tables.SCHEMA_META} WHERE key = ?",
            arrayOf(Schema.MetaKeys.SCHEMA_VERSION),
        ).use { c ->
            if (!c.moveToNext()) null else c.getBlob(0).decodeToString().toIntOrNull()
        }
    }
}
