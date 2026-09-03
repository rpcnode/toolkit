package rpcnode.toolkit.shared.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.Path
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
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        val dataSource = SQLiteDataSource(SQLiteConfig().apply { enforceForeignKeys(true) }).apply {
            this.url = url
        }
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
