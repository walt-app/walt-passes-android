package `is`.walt.passes.storage

/**
 * Snapshot of the v1 schema. Hard-coded so migration tests see the *historical* shape,
 * not whatever the current [Schema.DDL] happens to declare. Shared by every migration
 * test class; each walks v1 -> N over this snapshot with [Schema.MIGRATIONS].
 */
internal val V1_SCHEMA_SNAPSHOT: List<String> = listOf(
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
