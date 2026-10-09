package rpcnode.toolkit.panel.infrastructure.filesystem

import dev.dirs.ProjectDirectories
import java.nio.file.Path
import net.harawata.appdirs.AppDirsFactory

/** Config/cache via `dev.dirs:directories`. Logs via `net.harawata:appdirs` (`getUserLogDir`). */
class ProjectServerDirectories
{
    private val dirs = ProjectDirectories.from("dev", "rpcnode", "rpcnode-server")
        ?: error("cannot resolve rpcnode-server directories")

    fun configDir(): Path = requireDir(dirs.configDir, "config")

    fun cacheDir(): Path = requireDir(dirs.cacheDir, "cache")

    fun logDir(): Path = requireDir(
        AppDirsFactory.getInstance().getUserLogDir("rpcnode-server", null, "rpcnode"),
        "log",
    )
}

fun defaultServerLogFile(
    logFileEnv: String? = System.getenv("SERVER_LOG_FILE"),
    logDir: Path,
): Path
{
    val fromEnv = logFileEnv?.trim()?.ifEmpty { null }
    if (fromEnv != null)
    {
        return Path.of(fromEnv)
    }
    return logDir.resolve("server.log")
}

private fun requireDir(raw: String?, label: String): Path
{
    val value = raw?.trim()?.ifEmpty { null }
        ?: error("cannot resolve rpcnode-server $label directory")
    return Path.of(value)
}
