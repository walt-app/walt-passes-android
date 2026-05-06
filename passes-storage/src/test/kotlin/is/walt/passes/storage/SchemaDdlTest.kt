package `is`.walt.passes.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.sql.DriverManager
import java.sql.SQLException

/**
 * JVM-side verification that [Schema.DDL] executes cleanly on a stock SQLite engine. The
 * Android implementation runs the same DDL through SQLCipher; SQLCipher is wire-compatible
 * with SQLite, so a JVM-side green here is a strong signal that the schema is well-formed
 * before instrumentation tests get involved. Backed by xerial `sqlite-jdbc`.
 *
 * These tests deliberately do NOT depend on Android, SQLCipher, or any native library.
 */
class SchemaDdlTest {

    private fun openMemoryDb() = DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun applyDdl(): java.sql.Connection {
        val conn = openMemoryDb()
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA foreign_keys = ON")
            for (sql in Schema.DDL) {
                stmt.execute(sql)
            }
        }
        return conn
    }

    @Test
    fun ddlExecutesCleanlyAndCreatesTheSixDocumentedTables() {
        applyDdl().use { conn ->
            val tables = mutableSetOf<String>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")
                while (rs.next()) tables.add(rs.getString(1))
            }
            assertThat(tables).containsAtLeast(
                Schema.Tables.SCHEMA_META,
                Schema.Tables.PASSES,
                Schema.Tables.PASS_IMAGES,
                Schema.Tables.PASS_LOCALES,
                Schema.Tables.DOCUMENTS,
                Schema.Tables.DOCUMENT_THUMBNAILS,
            )
        }
    }

    @Test
    fun ddlCreatesTheFourDocumentedIndexes() {
        applyDdl().use { conn ->
            val indexes = mutableSetOf<String>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'",
                )
                while (rs.next()) indexes.add(rs.getString(1))
            }
            assertThat(indexes).containsAtLeast(
                "idx_passes_type",
                "idx_passes_expiration",
                "idx_passes_identity",
                "idx_documents_imported_at",
            )
        }
    }

    @Test
    fun uniqueIdentityIndexRejectsDuplicateImports() {
        applyDdl().use { conn ->
            val insert = conn.prepareStatement(
                "INSERT INTO ${Schema.Tables.PASSES}" +
                    "(type, serial_number, organization_name, description, voided, " +
                    "signature_status_kind, pass_json, created_at_epoch_ms, updated_at_epoch_ms) " +
                    "VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?)",
            )
            fun insertSample(serial: String) {
                insert.setString(1, "BoardingPass")
                insert.setString(2, serial)
                insert.setString(3, "AcmeAir")
                insert.setString(4, "AcmeAir Boarding Pass")
                insert.setString(5, "AppleVerified")
                insert.setBytes(6, byteArrayOf(0))
                insert.setLong(7, 1L)
                insert.setLong(8, 1L)
                insert.executeUpdate()
            }
            insertSample("AAA")
            try {
                insertSample("AAA")
                error("expected uniqueness violation")
            } catch (expected: SQLException) {
                assertThat(expected.message).contains("UNIQUE")
            }
        }
    }

    @Test
    fun foreignKeyCascadeDropsImageAndLocaleRowsWithTheParentPass() {
        applyDdl().use { conn ->
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
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

            conn.prepareStatement("DELETE FROM ${Schema.Tables.PASSES} WHERE id = 1")
                .use { it.executeUpdate() }

            conn.createStatement().use { stmt ->
                val imageRows = stmt.executeQuery(
                    "SELECT COUNT(*) FROM ${Schema.Tables.PASS_IMAGES}",
                ).also { it.next() }.getInt(1)
                val localeRows = stmt.executeQuery(
                    "SELECT COUNT(*) FROM ${Schema.Tables.PASS_LOCALES}",
                ).also { it.next() }.getInt(1)
                assertThat(imageRows).isEqualTo(0)
                assertThat(localeRows).isEqualTo(0)
            }
        }
    }
}
