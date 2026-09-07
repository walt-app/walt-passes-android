package `is`.walt.passes.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * JVM-side verification of the migration hops v1 through v7, each applied over
 * [V1_SCHEMA_SNAPSHOT] via [openDbAtVersion]. The v7 -> v8 hop lives in [PassArchivesMigrationTest].
 */
class SchemaMigrationTest {

    private fun openMemoryDb(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        return conn
    }

    @Test
    fun migrationFromV5AddsDocumentFormatAndDimensionColumns() {
        openDbAtVersion(5).use { conn ->
            for (sql in Schema.MIGRATIONS.getValue(5)) {
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
                "format",
                "width_px",
                "height_px",
            )
        }
    }

    @Test
    fun migrationFromV5DefaultsExistingDocumentsToPdfFormatWithNullDimensions() {
        openDbAtVersion(5).use { conn ->

            // A pre-existing v5 document row (PDF-only era; no format/dimension columns yet).
            conn.prepareStatement(
                "INSERT INTO ${Schema.Tables.DOCUMENTS}" +
                    "(id, display_label, pdf_bytes, byte_count, page_count, imported_at_epoch_ms) " +
                    "VALUES (1, 'old.pdf', x'00', 1, 3, 1)",
            ).use { it.executeUpdate() }

            for (sql in Schema.MIGRATIONS.getValue(5)) {
                conn.createStatement().use { it.execute(sql) }
            }

            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT format, page_count, width_px, height_px " +
                        "FROM ${Schema.Tables.DOCUMENTS} WHERE id = 1",
                )
                rs.next()
                assertThat(rs.getString("format")).isEqualTo("pdf")
                assertThat(rs.getInt("page_count")).isEqualTo(3)
                rs.getInt("width_px")
                assertThat(rs.wasNull()).isTrue()
                rs.getInt("height_px")
                assertThat(rs.wasNull()).isTrue()
            }
        }
    }

    @Test
    fun migrationFromV1IntroducesDocumentsAndDocumentThumbnailsTables() {
        openDbAtVersion(1).use { conn ->

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
        openDbAtVersion(1).use { conn ->
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
        openDbAtVersion(1).use { conn ->
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
        openDbAtVersion(1).use { conn ->
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
    fun freshInstallAndFullMigrationChainLandAtTheSameSchema() {
        // DDL drift guard: Schema.DDL (fresh install) and the full v1 -> current
        // migration chain must produce the same `sqlite_master` rows for tables and
        // indexes. A future refactor that touches one path and misses the other would
        // silently produce divergent shapes between fresh installs and migrated
        // installs; this test surfaces the divergence at JVM-test time. Generalized
        // over all hops so a future Schema.VERSION bump does not need a new parity
        // test entry.
        val freshShape = openMemoryDb().use { conn ->
            conn.createStatement().use { stmt ->
                for (sql in Schema.DDL) stmt.execute(sql)
            }
            schemaShape(conn)
        }
        val migratedShape = openDbAtVersion(Schema.VERSION).use { conn ->
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
            //
            // Whitespace is normalized (runs collapsed to a single space, then trimmed)
            // because SQLite's `ALTER TABLE ADD COLUMN` rewrites sqlite_master.sql with
            // its own formatting (typically `..., new_col TEXT)` tacked onto the original
            // statement). The intent of this comparison is structural equivalence —
            // same tables, same columns, same types, same constraints — not byte-for-
            // byte source identity. Mismatched columns / types / constraint sets still
            // fail; only whitespace and comma placement are normalized.
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
                    normalizeSql(rs.getString(4) ?: ""),
                ).joinToString("|")
            }
        }
        return out
    }

    private fun normalizeSql(sql: String): String =
        sql
            .replace(Regex("\\s+"), " ")
            .replace(" ,", ",")
            .replace(" )", ")")
            .replace("( ", "(")
            .trim()

    @Test
    fun migrationFromV2IntroducesScannableCardsTable() {
        openDbAtVersion(2).use { conn ->
            for (sql in Schema.MIGRATIONS.getValue(2)) {
                conn.createStatement().use { it.execute(sql) }
            }

            val tables = mutableSetOf<String>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")
                while (rs.next()) tables.add(rs.getString(1))
            }
            assertThat(tables).contains(Schema.Tables.SCANNABLE_CARDS)
        }
    }

    @Test
    fun migrationFromV2AddsTheScannableCardsCreatedAtIndex() {
        openDbAtVersion(2).use { conn ->
            for (sql in Schema.MIGRATIONS.getValue(2)) {
                conn.createStatement().use { it.execute(sql) }
            }

            val indexes = mutableSetOf<String>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'",
                )
                while (rs.next()) indexes.add(rs.getString(1))
            }
            assertThat(indexes).contains("idx_scannable_cards_created_at")
        }
    }

    @Test
    fun scannableCardsTableAfterMigrationHasTheExpectedColumns() {
        openDbAtVersion(2).use { conn ->
            for (sql in Schema.MIGRATIONS.getValue(2)) {
                conn.createStatement().use { it.execute(sql) }
            }

            val columns = mutableSetOf<String>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA table_info(${Schema.Tables.SCANNABLE_CARDS})")
                while (rs.next()) columns.add(rs.getString("name"))
            }
            assertThat(columns).containsExactly(
                "id",
                "payload",
                "format",
                "label",
                "color_argb",
                "created_at_epoch_ms",
            )
        }
    }

    @Test
    fun preexistingV2DataSurvivesMigrationToV3() {
        openDbAtVersion(2).use { conn ->

            // Pre-migration: a v2 pass row and a v2 document row.
            conn.prepareStatement(
                "INSERT INTO ${Schema.Tables.PASSES}" +
                    "(id, type, serial_number, organization_name, description, voided, " +
                    "signature_status_kind, pass_json, created_at_epoch_ms, updated_at_epoch_ms) " +
                    "VALUES (1, 'BoardingPass', 'S1', 'AcmeAir', 'desc', 0, 'AppleVerified', " +
                    "x'00', 1, 1)",
            ).use { it.executeUpdate() }
            conn.prepareStatement(
                "INSERT INTO ${Schema.Tables.DOCUMENTS}" +
                    "(id, display_label, pdf_bytes, byte_count, page_count, imported_at_epoch_ms) " +
                    "VALUES (1, 'doc.pdf', x'00', 1, 1, 1)",
            ).use { it.executeUpdate() }

            for (sql in Schema.MIGRATIONS.getValue(2)) {
                conn.createStatement().use { it.execute(sql) }
            }

            conn.createStatement().use { stmt ->
                assertThat(
                    stmt.executeQuery("SELECT COUNT(*) FROM ${Schema.Tables.PASSES}")
                        .also { it.next() }.getInt(1),
                ).isEqualTo(1)
                assertThat(
                    stmt.executeQuery("SELECT COUNT(*) FROM ${Schema.Tables.DOCUMENTS}")
                        .also { it.next() }.getInt(1),
                ).isEqualTo(1)
                assertThat(
                    stmt.executeQuery("SELECT COUNT(*) FROM ${Schema.Tables.SCANNABLE_CARDS}")
                        .also { it.next() }.getInt(1),
                ).isEqualTo(0)
            }
        }
    }

    @Test
    fun migrationFromV3DropsColorArgbColumnFromScannableCards() {
        openDbAtVersion(3).use { conn ->

            for (sql in Schema.MIGRATIONS.getValue(3)) {
                conn.createStatement().use { it.execute(sql) }
            }

            val columns = mutableSetOf<String>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA table_info(${Schema.Tables.SCANNABLE_CARDS})")
                while (rs.next()) columns.add(rs.getString("name"))
            }
            assertThat(columns).containsExactly(
                "id",
                "payload",
                "format",
                "label",
                "created_at_epoch_ms",
            )
        }
    }

    @Test
    fun migrationFromV3PreservesScannableCardRowsAndIdentities() {
        openDbAtVersion(3).use { conn ->
            // Two pre-migration v3 rows. Explicit ids assert row identity survives the
            // table rewrite: the migration must INSERT...SELECT each existing id rather
            // than letting AUTOINCREMENT reassign them.
            insertV3ScannableCard(
                conn,
                id = 7L,
                payload = "1234567890128",
                format = "Ean13",
                label = "Grocery",
                colorArgb = 0xFF112233.toInt(),
                createdAtMs = 1_700_000_000_000L,
            )
            insertV3ScannableCard(
                conn,
                id = 9L,
                payload = "ZX-99",
                format = "Code128",
                label = "Cinema",
                colorArgb = null,
                createdAtMs = 1_700_000_010_000L,
            )

            for (sql in Schema.MIGRATIONS.getValue(3)) {
                conn.createStatement().use { it.execute(sql) }
            }

            assertThat(scannableCardRowsAfterMigration(conn)).containsExactly(
                "7|1234567890128|Ean13|Grocery|1700000000000",
                "9|ZX-99|Code128|Cinema|1700000010000",
            ).inOrder()
            // AUTOINCREMENT high-water mark survives: a fresh insert without an explicit
            // id must mint id > 9, not collide with surviving rows.
            assertThat(insertAfterMigrationAndReturnId(conn)).isGreaterThan(9L)
        }
    }

    @Suppress("LongParameterList")
    private fun insertV3ScannableCard(
        conn: Connection,
        id: Long,
        payload: String,
        format: String,
        label: String,
        colorArgb: Int?,
        createdAtMs: Long,
    ) {
        conn.prepareStatement(
            "INSERT INTO ${Schema.Tables.SCANNABLE_CARDS}" +
                "(id, payload, format, label, color_argb, created_at_epoch_ms) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setLong(1, id)
            ps.setString(2, payload)
            ps.setString(3, format)
            ps.setString(4, label)
            if (colorArgb == null) ps.setNull(5, java.sql.Types.INTEGER) else ps.setInt(5, colorArgb)
            ps.setLong(6, createdAtMs)
            ps.executeUpdate()
        }
    }

    private fun scannableCardRowsAfterMigration(conn: Connection): List<String> {
        val out = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(
                "SELECT id, payload, format, label, created_at_epoch_ms " +
                    "FROM ${Schema.Tables.SCANNABLE_CARDS} ORDER BY id",
            )
            while (rs.next()) {
                out += listOf(
                    rs.getLong("id").toString(),
                    rs.getString("payload"),
                    rs.getString("format"),
                    rs.getString("label"),
                    rs.getLong("created_at_epoch_ms").toString(),
                ).joinToString("|")
            }
        }
        return out
    }

    private fun insertAfterMigrationAndReturnId(conn: Connection): Long {
        conn.prepareStatement(
            "INSERT INTO ${Schema.Tables.SCANNABLE_CARDS}" +
                "(payload, format, label, created_at_epoch_ms) VALUES (?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, "AFTER")
            ps.setString(2, "Code128")
            ps.setString(3, "After")
            ps.setLong(4, 1_700_000_020_000L)
            ps.executeUpdate()
        }
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(
                "SELECT id FROM ${Schema.Tables.SCANNABLE_CARDS} WHERE payload = 'AFTER'",
            )
            rs.next()
            return rs.getLong("id")
        }
    }

    @Test
    fun migrationFromV3KeepsTheScannableCardsCreatedAtIndex() {
        openDbAtVersion(3).use { conn ->
            for (sql in Schema.MIGRATIONS.getValue(3)) {
                conn.createStatement().use { it.execute(sql) }
            }

            val indexes = mutableSetOf<String>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'",
                )
                while (rs.next()) indexes.add(rs.getString(1))
            }
            assertThat(indexes).contains("idx_scannable_cards_created_at")
        }
    }

    @Test
    fun preexistingV3PassesAndDocumentsSurviveMigrationToV4() {
        openDbAtVersion(3).use { conn ->

            // Sibling tables that the v3 -> v4 migration must leave untouched.
            conn.prepareStatement(
                "INSERT INTO ${Schema.Tables.PASSES}" +
                    "(id, type, serial_number, organization_name, description, voided, " +
                    "signature_status_kind, pass_json, created_at_epoch_ms, updated_at_epoch_ms) " +
                    "VALUES (1, 'BoardingPass', 'S1', 'AcmeAir', 'desc', 0, 'AppleVerified', " +
                    "x'00', 1, 1)",
            ).use { it.executeUpdate() }
            conn.prepareStatement(
                "INSERT INTO ${Schema.Tables.DOCUMENTS}" +
                    "(id, display_label, pdf_bytes, byte_count, page_count, imported_at_epoch_ms) " +
                    "VALUES (1, 'doc.pdf', x'00', 1, 1, 1)",
            ).use { it.executeUpdate() }

            for (sql in Schema.MIGRATIONS.getValue(3)) {
                conn.createStatement().use { it.execute(sql) }
            }

            conn.createStatement().use { stmt ->
                assertThat(
                    stmt.executeQuery("SELECT COUNT(*) FROM ${Schema.Tables.PASSES}")
                        .also { it.next() }.getInt(1),
                ).isEqualTo(1)
                assertThat(
                    stmt.executeQuery("SELECT COUNT(*) FROM ${Schema.Tables.DOCUMENTS}")
                        .also { it.next() }.getInt(1),
                ).isEqualTo(1)
            }
        }
    }

    @Test
    fun migrationFromV4AddsTheUserLabelColumnToPasses() {
        openDbAtVersion(4).use { conn ->

            for (sql in Schema.MIGRATIONS.getValue(4)) {
                conn.createStatement().use { it.execute(sql) }
            }

            val columns = mutableSetOf<String>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA table_info(${Schema.Tables.PASSES})")
                while (rs.next()) columns.add(rs.getString("name"))
            }
            assertThat(columns).contains("user_label")
        }
    }

    @Test
    fun migrationFromV4UserLabelColumnIsNullable() {
        openDbAtVersion(4).use { conn ->
            for (sql in Schema.MIGRATIONS.getValue(4)) {
                conn.createStatement().use { it.execute(sql) }
            }

            // A row inserted post-migration without specifying user_label must read NULL.
            // The column has no DEFAULT clause and is nullable; SQLite stores NULL.
            conn.prepareStatement(
                "INSERT INTO ${Schema.Tables.PASSES}" +
                    "(id, type, serial_number, organization_name, description, voided, " +
                    "signature_status_kind, pass_json, created_at_epoch_ms, updated_at_epoch_ms) " +
                    "VALUES (1, 'BoardingPass', 'S1', 'AcmeAir', 'desc', 0, 'AppleVerified', " +
                    "x'00', 1, 1)",
            ).use { it.executeUpdate() }
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT user_label FROM ${Schema.Tables.PASSES} WHERE id = 1",
                )
                rs.next()
                rs.getString("user_label")
                assertThat(rs.wasNull()).isTrue()
            }
        }
    }

    @Test
    fun preexistingV4PassRowSurvivesMigrationToV5WithNullUserLabel() {
        openDbAtVersion(4).use { conn ->

            // A pre-existing v4 pass row that pre-dates the user_label column.
            conn.prepareStatement(
                "INSERT INTO ${Schema.Tables.PASSES}" +
                    "(id, type, serial_number, organization_name, description, voided, " +
                    "signature_status_kind, pass_json, created_at_epoch_ms, updated_at_epoch_ms) " +
                    "VALUES (42, 'BoardingPass', 'S42', 'AcmeAir', 'desc', 0, 'AppleVerified', " +
                    "x'00', 100, 200)",
            ).use { it.executeUpdate() }

            for (sql in Schema.MIGRATIONS.getValue(4)) {
                conn.createStatement().use { it.execute(sql) }
            }

            // Existing row is preserved and gets a NULL user_label after the migration.
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT id, organization_name, user_label FROM ${Schema.Tables.PASSES}",
                )
                rs.next()
                assertThat(rs.getLong("id")).isEqualTo(42L)
                assertThat(rs.getString("organization_name")).isEqualTo("AcmeAir")
                rs.getString("user_label")
                assertThat(rs.wasNull()).isTrue()
                assertThat(rs.next()).isFalse()
            }
        }
    }

    @Test
    fun preexistingV1DataSurvivesMigration() {
        openDbAtVersion(1).use { conn ->

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
}
