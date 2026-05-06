package `is`.walt.passes.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * JVM-side verification of the v1 -> v2 schema migration. Mirrors [SchemaDdlTest]'s
 * approach: stock `sqlite-jdbc` standing in for SQLCipher, since SQLCipher is
 * wire-compatible with SQLite for DDL.
 *
 * The properties locked here:
 *
 *  1. A v1 database (passes/pass_images/pass_locales tables only) can have the v1 -> v2
 *     migration statements applied without error.
 *  2. After the migration, the `documents` and `document_thumbnails` tables exist with
 *     the expected columns, the `idx_documents_imported_at` index is present, and the
 *     foreign-key cascade on the thumbnails table is wired up.
 *  3. Pre-existing v1 data (a `passes` row plus children) is preserved across the
 *     migration: forward-only does not mean forward-and-truncate.
 *  4. The migration list is exactly the v1 entry today; future versions add to this.
 */
class SchemaMigrationTest {

    private fun openMemoryDb(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        return conn
    }

    /**
     * Applies the v1 schema (everything in [Schema.DDL] up to and including the v1
     * tables, i.e. excluding the v2-only `documents` and `document_thumbnails` tables
     * and the `idx_documents_imported_at` index). The migration test then runs only the
     * v1 -> v2 hop on top of this and asserts the result equals the full v2 schema.
     */
    private fun applyV1Ddl(conn: Connection) {
        // V1 was schema_meta + passes + 3 pass-side indexes + pass_images + pass_locales
        // = 7 statements at the head of Schema.DDL.
        conn.createStatement().use { stmt ->
            for (sql in V1_DDL) stmt.execute(sql)
        }
    }

    @Test
    fun migrationFromV1IntroducesDocumentsAndDocumentThumbnailsTables() {
        openMemoryDb().use { conn ->
            applyV1Ddl(conn)

            val migration = Schema.MIGRATIONS.getValue(1)
            conn.createStatement().use { stmt ->
                for (sql in migration) stmt.execute(sql)
            }

            val tables = mutableSetOf<String>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")
                while (rs.next()) tables.add(rs.getString(1))
            }
            assertThat(tables).containsAtLeast(
                Schema.Tables.DOCUMENTS,
                Schema.Tables.DOCUMENT_THUMBNAILS,
            )
        }
    }

    @Test
    fun migrationFromV1AddsTheImportedAtIndex() {
        openMemoryDb().use { conn ->
            applyV1Ddl(conn)
            for (sql in Schema.MIGRATIONS.getValue(1)) {
                conn.createStatement().use { it.execute(sql) }
            }

            val indexes = mutableSetOf<String>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'",
                )
                while (rs.next()) indexes.add(rs.getString(1))
            }
            assertThat(indexes).contains("idx_documents_imported_at")
        }
    }

    @Test
    fun documentsTableHasTheExpectedColumns() {
        openMemoryDb().use { conn ->
            applyV1Ddl(conn)
            for (sql in Schema.MIGRATIONS.getValue(1)) {
                conn.createStatement().use { it.execute(sql) }
            }

            val columns = mutableSetOf<String>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA table_info(${Schema.Tables.DOCUMENTS})")
                while (rs.next()) columns.add(rs.getString("name"))
            }
            assertThat(columns).containsExactly(
                "id",
                "display_label",
                "pdf_bytes",
                "byte_count",
                "page_count",
                "imported_at_epoch_ms",
            )
        }
    }

    @Test
    fun documentThumbnailsTableCascadesOnDocumentDelete() {
        openMemoryDb().use { conn ->
            applyV1Ddl(conn)
            for (sql in Schema.MIGRATIONS.getValue(1)) {
                conn.createStatement().use { it.execute(sql) }
            }

            // Insert a document + thumbnail.
            conn.prepareStatement(
                "INSERT INTO ${Schema.Tables.DOCUMENTS}" +
                    "(id, display_label, pdf_bytes, byte_count, page_count, imported_at_epoch_ms) " +
                    "VALUES (1, 'doc.pdf', x'00', 1, 1, 1)",
            ).use { it.executeUpdate() }
            conn.prepareStatement(
                "INSERT INTO ${Schema.Tables.DOCUMENT_THUMBNAILS} (document_id, bytes) " +
                    "VALUES (1, x'00')",
            ).use { it.executeUpdate() }

            conn.prepareStatement("DELETE FROM ${Schema.Tables.DOCUMENTS} WHERE id = 1")
                .use { it.executeUpdate() }

            conn.createStatement().use { stmt ->
                val thumbnailRows = stmt.executeQuery(
                    "SELECT COUNT(*) FROM ${Schema.Tables.DOCUMENT_THUMBNAILS}",
                ).also { it.next() }.getInt(1)
                assertThat(thumbnailRows).isEqualTo(0)
            }
        }
    }

    @Test
    fun migrationsCoverEveryHopFromV1ToCurrent() {
        // Compile-time-equivalent guard: bumping Schema.VERSION without adding the
        // matching migration entry is a developer-side mistake, not a user-data
        // condition. This assertion catches it at CI time so the runtime branch in
        // SqlCipherDatabaseFactory.buildMigrationChain (which reports Other) should
        // never fire in a shipped build.
        val expected = (1 until Schema.VERSION).toSet()
        assertThat(Schema.MIGRATIONS.keys).containsExactlyElementsIn(expected)
    }

    @Test
    fun freshInstallAndV1ToV2MigrationLandAtTheSameSchema() {
        // DDL drift guard: Schema.DDL (fresh install) and the v1 -> v2 migration must
        // produce the same `sqlite_master` rows for tables and indexes. A future
        // refactor that touches one path and misses the other would silently produce
        // divergent shapes between fresh installs and migrated installs; this test
        // surfaces the divergence at JVM-test time.
        val freshShape = openMemoryDb().use { conn ->
            conn.createStatement().use { stmt ->
                for (sql in Schema.DDL) stmt.execute(sql)
            }
            schemaShape(conn)
        }
        val migratedShape = openMemoryDb().use { conn ->
            applyV1Ddl(conn)
            for (sql in Schema.MIGRATIONS.getValue(1)) {
                conn.createStatement().use { it.execute(sql) }
            }
            schemaShape(conn)
        }
        assertThat(migratedShape).isEqualTo(freshShape)
    }

    private fun schemaShape(conn: Connection): Set<String> {
        val out = mutableSetOf<String>()
        conn.createStatement().use { stmt ->
            // `sql` is the DDL the engine reconstructed; comparing it catches column,
            // ordering, and constraint drift across both creation paths. Auto-named
            // sqlite_* internal indexes are excluded since their names are nondeterministic.
            val rs = stmt.executeQuery(
                "SELECT type, name, tbl_name, sql FROM sqlite_master " +
                    "WHERE name NOT LIKE 'sqlite_%' " +
                    "ORDER BY type, name",
            )
            while (rs.next()) {
                out += listOf(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4) ?: "",
                ).joinToString("|")
            }
        }
        return out
    }

    @Test
    fun preexistingV1DataSurvivesMigration() {
        openMemoryDb().use { conn ->
            applyV1Ddl(conn)

            // Pre-migration: a v1 pass row with image and locale children.
            conn.prepareStatement(
                "INSERT INTO ${Schema.Tables.PASSES}" +
                    "(id, type, serial_number, organization_name, description, voided, " +
                    "signature_status_kind, pass_json, created_at_epoch_ms, updated_at_epoch_ms) " +
                    "VALUES (1, 'BoardingPass', 'S1', 'AcmeAir', 'desc', 0, 'AppleVerified', " +
                    "x'00', 1, 1)",
            ).use { it.executeUpdate() }
            conn.prepareStatement(
                "INSERT INTO ${Schema.Tables.PASS_IMAGES} (pass_id, role, bytes) " +
                    "VALUES (1, 'Logo', x'00')",
            ).use { it.executeUpdate() }
            conn.prepareStatement(
                "INSERT INTO ${Schema.Tables.PASS_LOCALES} (pass_id, locale_tag, strings_json) " +
                    "VALUES (1, 'en', x'00')",
            ).use { it.executeUpdate() }

            for (sql in Schema.MIGRATIONS.getValue(1)) {
                conn.createStatement().use { it.execute(sql) }
            }

            conn.createStatement().use { stmt ->
                assertThat(
                    stmt.executeQuery("SELECT COUNT(*) FROM ${Schema.Tables.PASSES}")
                        .also { it.next() }.getInt(1),
                ).isEqualTo(1)
                assertThat(
                    stmt.executeQuery("SELECT COUNT(*) FROM ${Schema.Tables.PASS_IMAGES}")
                        .also { it.next() }.getInt(1),
                ).isEqualTo(1)
                assertThat(
                    stmt.executeQuery("SELECT COUNT(*) FROM ${Schema.Tables.PASS_LOCALES}")
                        .also { it.next() }.getInt(1),
                ).isEqualTo(1)
            }
        }
    }

    private companion object {
        /**
         * Snapshot of the v1 schema. Hard-coded so the test sees the *historical* shape,
         * not whatever the current Schema.DDL happens to declare. Any future v2 -> v3
         * migration will get its own test that walks v1 -> v2 -> v3 against the same
         * snapshot.
         */
        val V1_DDL: List<String> = listOf(
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
    }
}
