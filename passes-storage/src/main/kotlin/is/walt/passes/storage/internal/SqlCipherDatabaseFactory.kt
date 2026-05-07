package `is`.walt.passes.storage.internal

import android.content.Context
import android.util.Log
import `is`.walt.passes.storage.DatabaseKey
import `is`.walt.passes.storage.Schema
import `is`.walt.passes.storage.StorageError
import `is`.walt.passes.storage.StorageResult
import `is`.walt.passes.storage.UnknownStorageFailureKind
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Opens (or creates on first use) the `walt_passes.db` SQLCipher file with the supplied
 * [DatabaseKey], pins the SQLCipher v4 page format, runs [Schema.DDL] under a single
 * transaction on first use, and enables `PRAGMA foreign_keys=ON` so the `ON DELETE CASCADE`
 * chains for `pass_images` and `pass_locales` actually fire (SQLite/SQLCipher default this off).
 *
 * The native libraries are loaded lazily via `System.loadLibrary("sqlcipher")`, which is
 * idempotent on subsequent calls.
 */
internal object SqlCipherDatabaseFactory {
    // Logcat tag for setup-phase diagnosis. Logcat is local-only; this is NOT telemetry
    // (StorageTelemetryGuard's no-free-form-strings discipline still holds). The cause's
    // class and message are needed to root-cause a SQLCipher setup failure on a real
    // device, since the typed StorageFailureKind alone is opaque.
    private const val LOG_TAG: String = "PassesStorage"

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
            // The withBytes scope zeros our local copy on return; SQLCipher's internal
            // page-key derivation buffer survives for the lifetime of the open connection,
            // which is bounded by SqlCipherPassStore.close().
            SQLiteDatabase.openOrCreateDatabase(dbFile, rawKey, null, null)
        }

        // Setup phase: PRAGMA pinning, schema-version probe, and DDL/migration apply must
        // all succeed or the connection must close. A throw anywhere below leaks the open
        // SQLiteDatabase handle (a real symptom seen in wpass-aio logcat) unless guarded.
        return try {
            // Pin SQLCipher v4 page format defensively against a future major-version
            // default change. Issued before any other DDL/DML so the page format is locked
            // before tables get created.
            db.execSQL("PRAGMA cipher_compatibility = 4")
            db.execSQL("PRAGMA foreign_keys = ON")

            val onDiskVersion: Int? = readSchemaVersionIfPresent(db)
            when {
                onDiskVersion != null && onDiskVersion > Schema.VERSION -> {
                    db.close()
                    return StorageResult.Failure(StorageError.Unsupported(onDiskVersion))
                }
                onDiskVersion == Schema.VERSION -> return StorageResult.Success(db)
            }

            val statements: List<String> = if (onDiskVersion == null) {
                Schema.DDL
            } else {
                buildMigrationChain(onDiskVersion) ?: run {
                    // The DB is fine; the build is broken (someone bumped Schema.VERSION
                    // without adding a migration entry). Reporting this as DatabaseCorrupt
                    // would mislead telemetry into pointing at user data. The
                    // `migrationsCoverEveryHopFromV1ToCurrent` JVM test catches the mistake
                    // at CI time so this runtime path should never fire in a shipped build.
                    db.close()
                    return StorageResult.Failure(
                        StorageError.Unknown(kind = UnknownStorageFailureKind.Other),
                    )
                }
            }

            db.beginTransaction()
            try {
                for (statement in statements) {
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
            StorageResult.Success(db)
        } catch (e: Throwable) {
            // Logcat (local-only) carries the cause class and message; StorageTelemetryGuard
            // remains free of free-form strings (see KDoc on that interface). Without this
            // line the failure surfaces only as `unknown_kind=DatabaseCorrupt` and root-cause
            // is impossible to determine from a device.
            Log.w(LOG_TAG, "SqlCipher setup failed: ${e.javaClass.name}: ${e.message}", e)
            runCatching { db.close() }.onFailure { closeError ->
                Log.w(LOG_TAG, "Also failed closing db after setup error", closeError)
            }
            StorageResult.Failure(
                StorageError.Unknown(
                    kind = UnknownStorageFailureKind.DatabaseCorrupt,
                    cause = e,
                ),
            )
        }
    }

    /**
     * Builds the forward-only migration chain from `onDiskVersion` up to [Schema.VERSION],
     * or returns null if any required hop is missing from [Schema.MIGRATIONS]. Bumping
     * VERSION without adding a corresponding migration entry is the only way to hit the
     * null branch; the surrounding code reports it as `DatabaseCorrupt`.
     */
    private fun buildMigrationChain(onDiskVersion: Int): List<String>? {
        val chain = ArrayList<String>()
        for (from in onDiskVersion until Schema.VERSION) {
            val hop = Schema.MIGRATIONS[from] ?: return null
            chain += hop
        }
        return chain
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
