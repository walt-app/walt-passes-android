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

    public const val VERSION: Int = 6

    public object Tables {
        public const val SCHEMA_META: String = "schema_meta"
        public const val PASSES: String = "passes"
        public const val PASS_IMAGES: String = "pass_images"
        public const val PASS_LOCALES: String = "pass_locales"
        public const val DOCUMENTS: String = "documents"
        public const val DOCUMENT_THUMBNAILS: String = "document_thumbnails"
        public const val SCANNABLE_CARDS: String = "scannable_cards"
    }

    public object MetaKeys {
        public const val SCHEMA_VERSION: String = "schema_version"
        public const val WRAPPED_DB_KEY: String = "wrapped_db_key"
        public const val WRAPPED_DB_KEY_IV: String = "wrapped_db_key_iv"
        public const val KEY_ALIAS: String = "key_alias"
        public const val KEY_BACKING: String = "key_backing"
    }

    /**
     * Statements that introduced the v3 scannable-cards table. Kept verbatim so a v2 -> v3
     * upgrade still produces the historical shape; the v3 -> v4 migration then rewrites
     * the table. Fresh installs skip this and go straight to [V4_SCANNABLE_CARD_TABLES].
     */
    private val V3_SCANNABLE_CARD_TABLES: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS scannable_cards (
            id                  INTEGER PRIMARY KEY AUTOINCREMENT,
            payload             TEXT    NOT NULL,
            format              TEXT    NOT NULL,
            label               TEXT    NOT NULL,
            color_argb          INTEGER,
            created_at_epoch_ms INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_scannable_cards_created_at " +
            "ON scannable_cards(created_at_epoch_ms)",
    )

    /**
     * Statements that introduce the v4 scannable-cards table — the v3 shape minus the
     * `color_argb` column. Used by [DDL] for fresh installs at v4 and beyond. Existing
     * v3 installs reach the same shape via the [V3_TO_V4_DROP_COLOR_COLUMN] table-rewrite
     * migration. `scannable_cards` lives in the same SQLCipher database as the existing
     * tables, so [BackupRulesContract.REQUIRED_EXCLUDES] already covers it.
     */
    private val V4_SCANNABLE_CARD_TABLES: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS scannable_cards (
            id                  INTEGER PRIMARY KEY AUTOINCREMENT,
            payload             TEXT    NOT NULL,
            format              TEXT    NOT NULL,
            label               TEXT    NOT NULL,
            created_at_epoch_ms INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_scannable_cards_created_at " +
            "ON scannable_cards(created_at_epoch_ms)",
    )

    /**
     * v4 -> v5 migration. Adds the nullable `user_label` column to the `passes` table for
     * the side-channel rename override (ADR 0007 D1). NULL = no override; existing rows
     * default to NULL so the post-migration shape preserves pre-migration display
     * behavior. No data transformation; no row can fail the migration.
     */
    private val V4_TO_V5_ADD_USER_LABEL: List<String> = listOf(
        "ALTER TABLE passes ADD COLUMN user_label TEXT",
    )

    /**
     * v3 -> v4 migration. Drops the `color_argb` column from the scannable_cards table.
     * Implemented as a table-rewrite (RENAME / CREATE / INSERT...SELECT / DROP) rather
     * than `ALTER TABLE DROP COLUMN` so the migration works on every SQLCipher version
     * the project ships on without depending on SQLite 3.35+ semantics. Row identity is
     * preserved by re-using each row's explicit `id` in the INSERT; SQLite's
     * AUTOINCREMENT bookkeeping in `sqlite_sequence` updates to max(id) on insert, so
     * subsequent inserts do not collide with surviving rows.
     */
    private val V3_TO_V4_DROP_COLOR_COLUMN: List<String> = listOf(
        "ALTER TABLE scannable_cards RENAME TO scannable_cards_v3",
        """
        CREATE TABLE scannable_cards (
            id                  INTEGER PRIMARY KEY AUTOINCREMENT,
            payload             TEXT    NOT NULL,
            format              TEXT    NOT NULL,
            label               TEXT    NOT NULL,
            created_at_epoch_ms INTEGER NOT NULL
        )
        """.trimIndent(),
        // Explicit column list intentional — SELECT * would pull color_argb and shift
        // column order on the target table (label -> created_at_epoch_ms et al.).
        """
        INSERT INTO scannable_cards (id, payload, format, label, created_at_epoch_ms)
            SELECT id, payload, format, label, created_at_epoch_ms FROM scannable_cards_v3
        """.trimIndent(),
        "DROP TABLE scannable_cards_v3",
        "CREATE INDEX IF NOT EXISTS idx_scannable_cards_created_at " +
            "ON scannable_cards(created_at_epoch_ms)",
    )

    /**
     * Statements that introduce the v2 document tables. Referenced from both [DDL] (for
     * fresh installs) and [MIGRATIONS]`[1]` (for v1 -> v2 upgrades) so the two paths
     * cannot drift.
     */
    private val V2_DOCUMENT_TABLES: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS documents (
            id                  INTEGER PRIMARY KEY AUTOINCREMENT,
            display_label       TEXT    NOT NULL,
            pdf_bytes           BLOB    NOT NULL,
            byte_count          INTEGER NOT NULL,
            page_count          INTEGER NOT NULL,
            imported_at_epoch_ms INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_documents_imported_at ON documents(imported_at_epoch_ms)",
        """
        CREATE TABLE IF NOT EXISTS document_thumbnails (
            document_id INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
            bytes       BLOB    NOT NULL,
            PRIMARY KEY (document_id)
        )
        """.trimIndent(),
    )

    /**
     * v5 -> v6 migration. Generalizes the `documents` table from PDF-only to PDF + image
     * (wpass-i9x step 4 / wpass-bsf). Adds:
     *
     *  - `format` — the document kind/container discriminator ('pdf' / 'png' / 'jpeg' /
     *    'webp'). `NOT NULL DEFAULT 'pdf'` so every pre-existing row (all PDFs before this
     *    version) reads back as a PDF without a data rewrite.
     *  - `width_px` / `height_px` — the decoded pixel dimensions of an image document
     *    (the bounded raster Walt produced in the `passes-image` sandbox). Nullable: NULL
     *    for PDF rows, where page count rather than dimensions is the kind-specific field.
     *
     * Pure additive ALTERs; no data transformation, so no row can fail the migration. The
     * `pdf_bytes` blob column is reused verbatim to hold the original document bytes
     * regardless of kind (PDF bytes or original image bytes) — renaming it would force a
     * table rewrite for no audit gain; `loadDocumentBytes` is already kind-agnostic.
     */
    private val V5_TO_V6_ADD_DOCUMENT_FORMAT: List<String> = listOf(
        "ALTER TABLE documents ADD COLUMN format TEXT NOT NULL DEFAULT 'pdf'",
        "ALTER TABLE documents ADD COLUMN width_px INTEGER",
        "ALTER TABLE documents ADD COLUMN height_px INTEGER",
    )

    /**
     * The DDL block that brings a fresh database to [VERSION]. Statements are listed in
     * dependency order (parent tables before child tables); they are executed in a single
     * transaction by the implementation.
     *
     * The `documents` table is created in its historical v2 6-column shape and then brought
     * to the v6 shape by appending [V5_TO_V6_ADD_DOCUMENT_FORMAT]'s ALTERs, exactly as the
     * migration chain does. Baking the new columns into the CREATE instead would diverge the
     * fresh-install `sqlite_master.sql` from the migrated one and break the
     * `freshInstallAndFullMigrationChainLandAtTheSameSchema` parity guard.
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
            updated_at_epoch_ms   INTEGER NOT NULL,
            user_label            TEXT
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
    ) + V2_DOCUMENT_TABLES + V4_SCANNABLE_CARD_TABLES + V5_TO_V6_ADD_DOCUMENT_FORMAT

    /**
     * Schema migrations, keyed by `fromVersion`. Forward-only per ADR 0002. Each entry's
     * statements are executed inside a single transaction; the
     * `schema_meta.schema_version` row is bumped to `fromVersion + 1` in the same
     * transaction so a partial upgrade is impossible.
     *
     * v1 -> v2 introduces `documents` and `document_thumbnails` for PDF document support
     * (ADR 0005). The new tables live in the same SQLCipher database, so no XML / Auto
     * Backup change is needed: the file-level exclusion already covers them.
     *
     * v2 -> v3 introduces `scannable_cards` for user-generated scannable artifacts.
     *
     * v3 -> v4 drops the now-unused `color_argb` column from `scannable_cards`. The
     * consumer no longer reads or writes the field (walt-android `wlt-z17`); the
     * column was dormant user-private data on disk. Row identity is preserved; per-row
     * colour bytes are lost, which is intentional — Walt already stopped reading them.
     *
     * v4 -> v5 adds the nullable `user_label` column to `passes` for the side-channel
     * rename override (ADR 0007). Pure additive change; existing rows default to NULL
     * (no override) so display behavior is unchanged for pre-migration data.
     *
     * v5 -> v6 generalizes `documents` from PDF-only to PDF + image (ADR 0005, amended for
     * wpass-i9x): adds the `format` discriminator and nullable `width_px` / `height_px`.
     * Pure additive; existing rows default to `format = 'pdf'`.
     */
    public val MIGRATIONS: Map<Int, List<String>> = mapOf(
        1 to V2_DOCUMENT_TABLES,
        2 to V3_SCANNABLE_CARD_TABLES,
        3 to V3_TO_V4_DROP_COLOR_COLUMN,
        4 to V4_TO_V5_ADD_USER_LABEL,
        5 to V5_TO_V6_ADD_DOCUMENT_FORMAT,
    )
}
