package `is`.walt.passes.storage.internal

import android.content.Context
import `is`.walt.passes.storage.DatabaseKey
import `is`.walt.passes.storage.Schema
import net.sqlcipher.database.SQLiteDatabase
import java.io.File

/**
 * Opens (or creates on first use) the `walt_passes.db` SQLCipher file with the supplied
 * [DatabaseKey], runs [Schema.DDL] under a single transaction on first use, and enables
 * `PRAGMA foreign_keys=ON` so the `ON DELETE CASCADE` chains for `pass_images` and
 * `pass_locales` actually fire (SQLite/SQLCipher default this off).
 *
 * The native libraries are loaded lazily here via `SQLiteDatabase.loadLibs(context)`, which
 * is idempotent.
 */
internal object SqlCipherDatabaseFactory {
    fun openOrCreate(context: Context, databaseKey: DatabaseKey): SQLiteDatabase {
        SQLiteDatabase.loadLibs(context.applicationContext)
        val dbFile: File = context.applicationContext.getDatabasePath(Schema.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()

        val db: SQLiteDatabase = databaseKey.withBytes { rawKey ->
            // SQLCipher accepts the raw 32 bytes directly; we hand the array to the
            // openOrCreateDatabase overload that takes a byte[] key, then withBytes zeros our
            // local copy on return. SQLCipher's internal page-key derivation buffer survives
            // for the lifetime of the open connection; that lifetime is bounded by close().
            SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, rawKey, null, null)
        }
        db.execSQL("PRAGMA foreign_keys = ON")

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
        } finally {
            db.endTransaction()
        }
        return db
    }
}
