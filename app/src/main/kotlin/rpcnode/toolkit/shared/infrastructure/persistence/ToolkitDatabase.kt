package rpcnode.toolkit.shared.infrastructure.persistence

import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

/** Shared SQLite file (`toolkit.db`). Flyway owns schema; slices map their own tables. */
class ToolkitDatabase(path: Path)
{
    val database: Database

    init
    {
        val parent = path.parent
        if (parent != null)
        {
            Files.createDirectories(parent)
        }
        // busy_timeout in the URL as well as SQLiteConfig — Exposed opens many connections;
        // without a wait, concurrent writers throw SQLITE_BUSY immediately.
        val absolute = path.toAbsolutePath()
        val url = "jdbc:sqlite:file:${absolute}?busy_timeout=30000"
        val raw = SQLiteDataSource(
            SQLiteConfig().apply {
                enforceForeignKeys(true)
                setBusyTimeout(30_000)
                setJournalMode(SQLiteConfig.JournalMode.WAL)
            },
        ).apply {
            this.url = url
        }
        // One live JDBC connection at a time — SQLite does not like parallel writers from
        // Ktor worker threads even with WAL + busy_timeout.
        val dataSource = SerialSqliteDataSource(raw)
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion("2")
            .load()
            .migrate()
        database = Database.connect(dataSource)
    }
}

/**
 * Mutex around [DataSource.getConnection]: the caller holds the lock until [Connection.close].
 * Nested [org.jetbrains.exposed.sql.transaction] on the same thread still uses Exposed's
 * ThreadLocal connection (one getConnection per outer transaction).
 */
internal class SerialSqliteDataSource(
    private val inner: SQLiteDataSource,
) : DataSource
{
    private val lock = ReentrantLock()

    override fun getConnection(): Connection
    {
        lock.lock()
        return try
        {
            LockedConnection(inner.connection, lock)
        }
        catch (t: Throwable)
        {
            lock.unlock()
            throw t
        }
    }

    override fun getConnection(username: String?, password: String?): Connection = getConnection()

    override fun getLogWriter(): PrintWriter? = inner.logWriter

    override fun setLogWriter(out: PrintWriter?)
    {
        inner.logWriter = out
    }

    override fun getLoginTimeout(): Int = inner.loginTimeout

    override fun setLoginTimeout(seconds: Int)
    {
        inner.loginTimeout = seconds
    }

    override fun getParentLogger() = inner.parentLogger

    override fun <T> unwrap(iface: Class<T>): T
    {
        if (iface.isInstance(this))
        {
            @Suppress("UNCHECKED_CAST")
            return this as T
        }
        return inner.unwrap(iface)
    }

    override fun isWrapperFor(iface: Class<*>): Boolean =
        iface.isInstance(this) || inner.isWrapperFor(iface)
}

private class LockedConnection(
    private val raw: Connection,
    private val lock: ReentrantLock,
) : Connection by raw
{
    private val closed = AtomicBoolean(false)

    override fun close()
    {
        if (!closed.compareAndSet(false, true))
        {
            return
        }
        try
        {
            raw.close()
        }
        finally
        {
            lock.unlock()
        }
    }

    override fun isClosed(): Boolean = closed.get() || raw.isClosed

    override fun <T : Any> unwrap(iface: Class<T>): T
    {
        if (iface.isInstance(this))
        {
            return iface.cast(this)
        }
        return raw.unwrap(iface)
    }

    override fun isWrapperFor(iface: Class<*>): Boolean =
        iface.isInstance(this) || raw.isWrapperFor(iface)
}
