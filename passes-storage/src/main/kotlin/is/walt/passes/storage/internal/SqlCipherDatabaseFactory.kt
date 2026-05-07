package `is`.walt.passes.storage.internal

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import `is`.walt.passes.storage.DatabaseKey
import `is`.walt.passes.storage.Schema
import `is`.walt.passes.storage.StorageError
import `is`.walt.passes.storage.StorageResult
import `is`.walt.passes.storage.UnknownStorageFailureKind
import kotlinx.coroutines.CancellationException
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
    // Logcat tag for setup-phase diagnosis. Logcat is local-only AND we gate on the host
    // app's debuggable flag so release builds emit nothing. StorageTelemetryGuard's
    // no-free-form-strings discipline still holds; this is debug-only diagnostic, not
    // telemetry. The cause class and message are needed to root-cause a SQLCipher setup
    // failure on a real device, since the typed StorageFailureKind alone is opaque.
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
    ): StorageResult<OpenedDatabase> {
        loadLibsOnce()
        val dbFile: File = context.applicationContext.getDatabasePath(Schema.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        val isDebuggable: Boolean = isHostAppDebuggable(context)

        // The byte[] handed to SQLCipher must outlive every pool connection it keys.
        // SQLiteDatabaseConfiguration retains the array by reference; SQLiteConnection
        // re-reads it on every lazily-opened pool connection. retainAcross hands a
        // long-lived buffer to SQLCipher and returns a handle whose close() zeros it.
        // The handle's lifetime is bounded by SqlCipherPassStore.close(), which closes
        // the SQLiteDatabase first.
        var openedDb: SQLiteDatabase? = null
        val keyHandle: AutoCloseable = databaseKey.retainAcross { rawKey ->
            openedDb = SQLiteDatabase.openOrCreateDatabase(dbFile, rawKey, null, null)
        }
        val db: SQLiteDatabase = openedDb!!

        // Pin SQLCipher v4 page format defensively against a future major-version default
        // change. Issued before any other DDL/DML so the page format is locked before
        // tables get created. Probe the on-disk schema version after PRAGMA so any failure
        // here is mapped to DatabaseCorrupt with the cause attached.
        val onDiskVersion: Int? = try {
            db.execSQL("PRAGMA cipher_compatibility = 4")
            db.execSQL("PRAGMA foreign_keys = ON")
            readSchemaVersionIfPresent(db)
        } catch (e: CancellationException) {
            // Coroutine cancellation: surface, do NOT map to DatabaseCorrupt. Close
            // best-effort to release the native handle and zero the key buffer.
            closeQuietly(db, keyHandle, isDebuggable, phase = "PRAGMA/schema-probe")
            throw e
        } catch (e: Exception) {
            return databaseCorrupt(db, keyHandle, e, isDebuggable, phase = "PRAGMA/schema-probe")
        }

        if (onDiskVersion != null && onDiskVersion > Schema.VERSION) {
            // Future schema (user downgraded the wallet app). Close best-effort; if close
            // throws we still want to return Unsupported, NOT DatabaseCorrupt.
            closeQuietly(db, keyHandle, isDebuggable, phase = "future-schema-close")
            return StorageResult.Failure(StorageError.Unsupported(onDiskVersion))
        }
        if (onDiskVersion == Schema.VERSION) {
            return StorageResult.Success(OpenedDatabase(db, keyHandle))
        }

        val statements: List<String> = if (onDiskVersion == null) {
            Schema.DDL
        } else {
            buildMigrationChain(onDiskVersion) ?: run {
                // The DB is fine; the build is broken (someone bumped Schema.VERSION
                // without adding a migration entry). Reporting this as DatabaseCorrupt
                // would mislead telemetry into pointing at user data; close best-effort
                // so a throwing close() does not flip the category. The
                // `migrationsCoverEveryHopFromV1ToCurrent` JVM test catches the mistake
                // at CI time so this runtime path should never fire in a shipped build.
                closeQuietly(db, keyHandle, isDebuggable, phase = "missing-migration-close")
                return StorageResult.Failure(
                    StorageError.Unknown(kind = UnknownStorageFailureKind.Other),
                )
            }
        }

        try {
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
        } catch (e: CancellationException) {
            closeQuietly(db, keyHandle, isDebuggable, phase = "DDL/migration")
            throw e
        } catch (e: Exception) {
            return databaseCorrupt(db, keyHandle, e, isDebuggable, phase = "DDL/migration")
        }
        return StorageResult.Success(OpenedDatabase(db, keyHandle))
    }

    private fun isHostAppDebuggable(context: Context): Boolean =
        context.applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun databaseCorrupt(
        db: SQLiteDatabase,
        keyHandle: AutoCloseable,
        cause: Exception,
        isDebuggable: Boolean,
        phase: String,
    ): StorageResult<OpenedDatabase> {
        if (isDebuggable) {
            Log.w(LOG_TAG, "SqlCipher $phase failed: ${cause.javaClass.name}: ${cause.message}", cause)
        }
        closeQuietly(db, keyHandle, isDebuggable, phase = "$phase-close")
        return StorageResult.Failure(
            StorageError.Unknown(
                kind = UnknownStorageFailureKind.DatabaseCorrupt,
                cause = cause,
            ),
        )
    }

    private fun closeQuietly(
        db: SQLiteDatabase,
        keyHandle: AutoCloseable,
        isDebuggable: Boolean,
        phase: String,
    ) {
        runCatching { db.close() }.onFailure { closeError ->
            if (isDebuggable) {
                Log.w(
                    LOG_TAG,
                    "Also failed during $phase: ${closeError.javaClass.name}: ${closeError.message}",
                    closeError,
                )
            }
        }
        // Zero the key buffer regardless of whether db.close() threw, so the raw key
        // never outlives a failed setup phase.
        runCatching { keyHandle.close() }.onFailure { closeError ->
            if (isDebuggable) {
                Log.w(
                    LOG_TAG,
                    "Also failed zeroing key during $phase: ${closeError.javaClass.name}: ${closeError.message}",
                    closeError,
                )
            }
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

/**
 * The result of [SqlCipherDatabaseFactory.openOrCreate]. Bundles the SQLCipher handle
 * with the [AutoCloseable] that zeros the raw-key buffer SQLCipher's connection pool
 * holds by reference. The owner ([SqlCipherPassStore]) MUST close the SQLiteDatabase
 * first, then the keyHandle - reversing the order would zero the buffer while the
 * pool may still be re-keying connections.
 */
internal data class OpenedDatabase(
    val db: SQLiteDatabase,
    val keyHandle: AutoCloseable,
)
