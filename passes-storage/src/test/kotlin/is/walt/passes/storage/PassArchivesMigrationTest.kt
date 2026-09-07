package `is`.walt.passes.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * JVM-side verification of the v7 -> v8 hop (wpass-59i.1): the `pass_archives` sidecar
 * for the retained original `.pkpass` bytes. Same approach as [SchemaMigrationTest]:
 * stock `sqlite-jdbc` standing in for SQLCipher, walking the historical
 * [V1_SCHEMA_SNAPSHOT] up to v7 before applying the hop under test.
 *
 * Properties locked here:
 *
 *  1. The hop creates `pass_archives` keyed 1:1 by pass id.
 *  2. The hop leaves the `passes` table shape untouched: the archive is a sidecar, not
 *     a column, so the list / detail queries cannot pay for it.
 *  3. A pre-existing v7 pass (with image and locale children) survives with NO archive:
 *     there is nothing to backfill from, so it reads back as NULL through a LEFT JOIN.
 *  4. The sidecar cascades with the parent pass.
 */
class PassArchivesMigrationTest {

    private fun openV7Db(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA foreign_keys = ON")
            for (sql in V1_SCHEMA_SNAPSHOT) stmt.execute(sql)
            for (from in 1 until 7) {
                for (sql in Schema.MIGRATIONS.getValue(from)) stmt.execute(sql)
            }
        }
        return conn
    }

    private fun applyV7ToV8(conn: Connection) {
        conn.createStatement().use { stmt ->
            for (sql in Schema.MIGRATIONS.getValue(7)) stmt.execute(sql)
        }
    }

    private fun insertV7Pass(conn: Connection, id: Long) {
        conn.prepareStatement(
            "INSERT INTO ${Schema.Tables.PASSES}" +
                "(id, type, serial_number, organization_name, description, voided, " +
                "signature_status_kind, pass_json, created_at_epoch_ms, updated_at_epoch_ms) " +
                "VALUES (?, 'BoardingPass', 'S$id', 'AcmeAir', 'desc', 0, 'AppleVerified', x'00', 100, 200)",
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate()
        }
    }

    private fun passesColumns(conn: Connection): List<String> {
        val out = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA table_info(${Schema.Tables.PASSES})")
            while (rs.next()) out += rs.getString("name")
        }
        return out
    }

    private fun countRows(conn: Connection, table: String): Int =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM $table").also { it.next() }.getInt(1)
        }

    @Test
    fun migrationFromV7IntroducesThePassArchivesSidecarTable() {
        openV7Db().use { conn ->
            applyV7ToV8(conn)

            val columns = mutableSetOf<String>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA table_info(${Schema.Tables.PASS_ARCHIVES})")
                while (rs.next()) columns.add(rs.getString("name"))
            }
            assertThat(columns).containsExactly("pass_id", "bytes")
        }
    }

    @Test
    fun migrationFromV7LeavesThePassesTableShapeUntouched() {
        openV7Db().use { conn ->
            val before = passesColumns(conn)
            applyV7ToV8(conn)
            assertThat(passesColumns(conn)).isEqualTo(before)
        }
    }

    @Test
    fun preexistingV7PassSurvivesMigrationToV8WithNoArchive() {
        openV7Db().use { conn ->
            insertV7Pass(conn, id = 42L)
            conn.prepareStatement(
                "INSERT INTO ${Schema.Tables.PASS_IMAGES} (pass_id, role, bytes) VALUES (42, 'Logo', x'00')",
            ).use { it.executeUpdate() }
            conn.prepareStatement(
                "INSERT INTO ${Schema.Tables.PASS_LOCALES} (pass_id, locale_tag, strings_json) " +
                    "VALUES (42, 'en', x'00')",
            ).use { it.executeUpdate() }

            applyV7ToV8(conn)

            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT p.id, p.organization_name, a.bytes " +
                        "FROM ${Schema.Tables.PASSES} p " +
                        "LEFT JOIN ${Schema.Tables.PASS_ARCHIVES} a ON a.pass_id = p.id",
                )
                rs.next()
                assertThat(rs.getLong("id")).isEqualTo(42L)
                assertThat(rs.getString("organization_name")).isEqualTo("AcmeAir")
                rs.getBytes("bytes")
                assertThat(rs.wasNull()).isTrue()
                assertThat(rs.next()).isFalse()
            }
            assertThat(countRows(conn, Schema.Tables.PASS_IMAGES)).isEqualTo(1)
            assertThat(countRows(conn, Schema.Tables.PASS_LOCALES)).isEqualTo(1)
            assertThat(countRows(conn, Schema.Tables.PASS_ARCHIVES)).isEqualTo(0)
        }
    }

    @Test
    fun passArchivesAfterMigrationCascadesOnPassDelete() {
        openV7Db().use { conn ->
            applyV7ToV8(conn)
            insertV7Pass(conn, id = 1L)
            conn.prepareStatement(
                "INSERT INTO ${Schema.Tables.PASS_ARCHIVES} (pass_id, bytes) VALUES (1, x'504b0304')",
            ).use { it.executeUpdate() }

            conn.prepareStatement("DELETE FROM ${Schema.Tables.PASSES} WHERE id = 1")
                .use { it.executeUpdate() }

            assertThat(countRows(conn, Schema.Tables.PASS_ARCHIVES)).isEqualTo(0)
        }
    }
}
