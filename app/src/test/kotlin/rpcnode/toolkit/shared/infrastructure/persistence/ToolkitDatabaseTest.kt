package rpcnode.toolkit.shared.infrastructure.persistence

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolkitDatabaseTest
{
    @Test
    fun migrate_applies_init_then_settings()
    {
        val path = Files.createTempDirectory("toolkit-db").resolve("toolkit.db")
        ToolkitDatabase(path)
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { c ->
            c.createStatement().executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='settings'",
            ).use { rs ->
                assertTrue(rs.next())
            }
            c.createStatement().executeQuery(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank",
            ).use { rs ->
                assertTrue(rs.next())
                assertEquals("1", rs.getString(1))
                assertTrue(rs.next())
                assertEquals("2", rs.getString(1))
                assertTrue(rs.next())
                assertEquals("3", rs.getString(1))
                assertTrue(rs.next())
                assertEquals("4", rs.getString(1))
                assertTrue(rs.next())
                assertEquals("5", rs.getString(1))
                assertTrue(rs.next())
                assertEquals("6", rs.getString(1))
                assertTrue(rs.next())
                assertEquals("7", rs.getString(1))
                assertTrue(rs.next())
                assertEquals("8", rs.getString(1))
                assertTrue(rs.next())
                assertEquals("9", rs.getString(1))
                assertTrue(rs.next())
                assertEquals("10", rs.getString(1))
                assertTrue(rs.next())
                assertEquals("11", rs.getString(1))
                assertTrue(rs.next())
                assertEquals("12", rs.getString(1))
                assertTrue(rs.next())
                assertEquals("13", rs.getString(1))
                assertTrue(rs.next())
                assertEquals("14", rs.getString(1))
                assertTrue(rs.next())
                assertEquals("15", rs.getString(1))
                assertTrue(rs.next())
                assertEquals("16", rs.getString(1))
            }
        }
    }

    @Test
    fun reopen_same_file()
    {
        val path = Files.createTempDirectory("toolkit-db-reopen").resolve("toolkit.db")
        ToolkitDatabase(path)
        ToolkitDatabase(path)
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { c ->
            c.createStatement().executeQuery(
                "SELECT COUNT(*) FROM flyway_schema_history",
            ).use { rs ->
                assertTrue(rs.next())
                assertEquals(16, rs.getInt(1))
            }
        }
    }

    @Test
    fun opens_with_wal_and_busy_timeout()
    {
        val path = Files.createTempDirectory("toolkit-db-wal").resolve("toolkit.db")
        ToolkitDatabase(path)
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { c ->
            c.createStatement().executeQuery("PRAGMA journal_mode").use { rs ->
                assertTrue(rs.next())
                assertEquals("wal", rs.getString(1).lowercase())
            }
        }
    }

    @Test
    fun concurrent_writes_do_not_busy()
    {
        val path = Files.createTempDirectory("toolkit-db-serial").resolve("toolkit.db")
        val db = ToolkitDatabase(path)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = (1..8).map { n ->
            Thread {
                try
                {
                    repeat(20) { i ->
                        org.jetbrains.exposed.sql.transactions.transaction(db.database) {
                            val key = "k$n"
                            val now = java.time.Instant.now().toString()
                            val row =
                                rpcnode.toolkit.settings.infrastructure.persistence.SettingEntity.findById(key)
                            if (row == null)
                            {
                                rpcnode.toolkit.settings.infrastructure.persistence.SettingEntity.new(key) {
                                    settingValue = "$i"
                                    updatedAt = now
                                }
                            }
                            else
                            {
                                row.settingValue = "$i"
                                row.updatedAt = now
                            }
                        }
                    }
                }
                catch (t: Throwable)
                {
                    errors.add(t)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(errors.isEmpty(), errors.joinToString { it.message ?: it.toString() })
    }

    @Test
    fun existing_table_without_flyway_is_baselined()
    {
        val path = Files.createTempDirectory("toolkit-db-legacy").resolve("toolkit.db")
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { c ->
            c.createStatement().execute(
                """
                CREATE TABLE panel_settings (
                  key TEXT PRIMARY KEY,
                  value TEXT NOT NULL DEFAULT '',
                  updated_at TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
            c.createStatement().execute(
                "INSERT INTO panel_settings (key, value) VALUES ('install_origin', 'http://127.0.0.1:8093')",
            )
        }
        ToolkitDatabase(path)
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { c ->
            c.createStatement().executeQuery(
                "SELECT value FROM settings WHERE key = 'install_origin'",
            ).use { rs ->
                assertTrue(rs.next())
                assertEquals("http://127.0.0.1:8093", rs.getString(1))
            }
            c.createStatement().executeQuery(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank",
            ).use { rs ->
                assertTrue(rs.next())
                assertEquals("2", rs.getString(1))
            }
        }
    }
}
