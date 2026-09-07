package `is`.walt.passes.storage.internal

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.storage.Schema
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Pins the query shapes of [SqlCipherPassStore] on a stock SQLite engine (xerial
 * `sqlite-jdbc`, wire-compatible with SQLCipher for SQL): the list / detail / summary
 * statements select from `passes` only and never touch the `pass_archives` sidecar, so
 * the wallet list and detail views cannot pay for the retained archive by accident. The
 * SQLCipher binding itself is exercised by the instrumentation tests.
 */
class SqlCipherPassStoreQueryShapeTest {

    private fun openWithSchema(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA foreign_keys = ON")
            for (sql in Schema.DDL) stmt.execute(sql)
        }
        return conn
    }

    private fun insertPass(conn: Connection, id: Long) {
        conn.prepareStatement(
            "INSERT INTO ${Schema.Tables.PASSES}" +
                "(id, type, serial_number, organization_name, description, voided, " +
                "signature_status_kind, pass_json, created_at_epoch_ms, updated_at_epoch_ms) " +
                "VALUES (?, 'BoardingPass', 'S$id', 'AcmeAir', 'desc', 0, 'AppleVerified', x'00', 1, 1)",
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate()
        }
    }

    @Test
    fun listDetailAndSummaryStatementsNeverReferenceTheArchiveSidecar() {
        val hotPathSql = listOf(
            SqlCipherPassStore.LIST_SUMMARIES_SQL,
            SqlCipherPassStore.LOAD_BY_ID_SQL,
            SqlCipherPassStore.SUMMARY_BY_ID_SQL,
        )
        for (sql in hotPathSql) {
            assertThat(sql).doesNotContain(Schema.Tables.PASS_ARCHIVES)
            assertThat(sql).doesNotContain("JOIN")
            assertThat(sql).contains("FROM ${Schema.Tables.PASSES}")
        }
    }

    @Test
    fun hotPathStatementsExecuteAgainstTheCurrentSchema() {
        openWithSchema().use { conn ->
            insertPass(conn, id = 1L)
            conn.prepareStatement(SqlCipherPassStore.LIST_SUMMARIES_SQL).use { ps ->
                val rs = ps.executeQuery()
                assertThat(rs.next()).isTrue()
                assertThat(rs.getLong("id")).isEqualTo(1L)
            }
            for (sql in listOf(SqlCipherPassStore.LOAD_BY_ID_SQL, SqlCipherPassStore.SUMMARY_BY_ID_SQL)) {
                conn.prepareStatement(sql).use { ps ->
                    ps.setLong(1, 1L)
                    val rs = ps.executeQuery()
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString("serial_number")).isEqualTo("S1")
                }
            }
        }
    }

    @Test
    fun archiveStatementDistinguishesUnknownLegacyAndRetainedRows() {
        openWithSchema().use { conn ->
            insertPass(conn, id = 1L)
            insertPass(conn, id = 2L)
            conn.prepareStatement(
                "INSERT INTO ${Schema.Tables.PASS_ARCHIVES} (pass_id, bytes) VALUES (2, x'504b0304')",
            ).use { it.executeUpdate() }

            conn.prepareStatement(SqlCipherPassStore.LOAD_ARCHIVE_SQL).use { ps ->
                // Unknown pass: no row at all.
                ps.setLong(1, 404L)
                assertThat(ps.executeQuery().next()).isFalse()

                // Legacy pass: a row whose bytes are NULL.
                ps.setLong(1, 1L)
                val legacy = ps.executeQuery()
                assertThat(legacy.next()).isTrue()
                legacy.getBytes(1)
                assertThat(legacy.wasNull()).isTrue()

                // Retained pass: the bytes as stored.
                ps.setLong(1, 2L)
                val retained = ps.executeQuery()
                assertThat(retained.next()).isTrue()
                assertThat(retained.getBytes(1)).isEqualTo(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            }
        }
    }
}
