package `is`.walt.passes.storage

/**
 * The on-disk schema. Single source of truth for table and column names, version, and
 * DDL statements. The Android implementation executes [DDL] verbatim against the
 * SQLCipher-opened database; JVM tests execute the same statements against an in-memory
 * SQLite to verify schema-roundtrip behavior without Android dependencies.
 *
 * Bumping [VERSION] requires a new entry in [MIGRATIONS] and a corresponding test that
 * walks every prior version up to current. Forward-only: rollback is not supported, per
 * ADR 0002 (a downgraded build refuses to open the DB and surfaces
 * [StorageError.Unsupported]).
 */
public object Schema {
    public const val DATABASE_NAME: String = "walt_passes.db"

    public const val VERSION: Int = 1

    public object Tables {
        public const val SCHEMA_META: String = "schema_meta"
        public const val PASSES: String = "passes"
        public const val PASS_IMAGES: String = "pass_images"
        public const val PASS_LOCALES: String = "pass_locales"
    }

    public object MetaKeys {
        public const val SCHEMA_VERSION: String = "schema_version"
        public const val WRAPPED_DB_KEY: String = "wrapped_db_key"
        public const val WRAPPED_DB_KEY_IV: String = "wrapped_db_key_iv"
        public const val KEY_ALIAS: String = "key_alias"
        public const val KEY_BACKING: String = "key_backing"
    }

    /**
     * The DDL block that brings a fresh database to [VERSION]. Statements are listed in
     * dependency order (parent tables before child tables); they are executed in a single
     * transaction by the implementation.
     */
    public val DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS schema_meta (
            key   TEXT PRIMARY KEY NOT NULL,
            value BLOB NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS passes (
            id                    INTEGER PRIMARY KEY AUTOINCREMENT,
            type                  TEXT    NOT NULL,
            serial_number         TEXT    NOT NULL,
            organization_name     TEXT    NOT NULL,
            description           TEXT    NOT NULL,
            expiration_epoch_ms   INTEGER,
            voided                INTEGER NOT NULL DEFAULT 0,
            signature_status_kind TEXT    NOT NULL,
            pass_json             BLOB    NOT NULL,
            created_at_epoch_ms   INTEGER NOT NULL,
            updated_at_epoch_ms   INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_passes_type ON passes(type)",
        "CREATE INDEX IF NOT EXISTS idx_passes_expiration ON passes(expiration_epoch_ms)",
        """
        CREATE UNIQUE INDEX IF NOT EXISTS idx_passes_identity
            ON passes(type, serial_number, organization_name)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS pass_images (
            pass_id INTEGER NOT NULL REFERENCES passes(id) ON DELETE CASCADE,
            role    TEXT    NOT NULL,
            bytes   BLOB    NOT NULL,
            PRIMARY KEY (pass_id, role)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS pass_locales (
            pass_id      INTEGER NOT NULL REFERENCES passes(id) ON DELETE CASCADE,
            locale_tag   TEXT    NOT NULL,
            strings_json BLOB    NOT NULL,
            PRIMARY KEY (pass_id, locale_tag)
        )
        """.trimIndent(),
    )

    /**
     * Schema migrations. Empty at version 1; future entries follow the shape
     * `(fromVersion, listOf("ALTER ...", ...))`.
     */
    public val MIGRATIONS: Map<Int, List<String>> = emptyMap()
}
