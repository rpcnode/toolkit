package rpcnode.toolkit.agent.infrastructure.filesystem

import dev.dirs.ProjectDirectories
import java.nio.file.Path
import net.harawata.appdirs.AppDirsFactory
import rpcnode.toolkit.agent.domain.AgentDirectories

/** Config/cache via `dev.dirs:directories`. Logs via `net.harawata:appdirs` (`getUserLogDir`). */
class ProjectAgentDirectories(
    private val configDirEnv: String? = System.getenv("AGENT_CONFIG_DIR"),
    private val cacheDirEnv: String? = System.getenv("AGENT_CACHE_DIR"),
    private val logDirEnv: String? = System.getenv("AGENT_LOG_DIR"),
) : AgentDirectories
{
    private val dirs by lazy {
        ProjectDirectories.from("dev", "rpcnode", "rpcnode-agent")
            ?: error("cannot resolve rpcnode-agent directories")
    }

    override fun configDir(): Path = requireDir(overrideOr(configDirEnv) { dirs.configDir }, "config")

    override fun cacheDir(): Path = requireDir(overrideOr(cacheDirEnv) { dirs.cacheDir }, "cache")

    override fun logDir(): Path = requireDir(
        overrideOr(logDirEnv) {
            AppDirsFactory.getInstance().getUserLogDir("rpcnode-agent", null, "rpcnode")
        },
        "log",
    )
}

private fun overrideOr(env: String?, fallback: () -> String?): String? =
    env?.trim()?.ifEmpty { null } ?: fallback()

fun defaultAgentLogFile(
    logFileEnv: String? = System.getenv("AGENT_LOG_FILE"),
    logDir: Path,
): Path
{
    val fromEnv = logFileEnv?.trim()?.ifEmpty { null }
    if (fromEnv != null)
    {
        return Path.of(fromEnv)
    }
    return logDir.resolve("rpcnode-agent.log")
}

private fun requireDir(raw: String?, label: String): Path
{
    val value = raw?.trim()?.ifEmpty { null }
        ?: error("cannot resolve rpcnode-agent $label directory")
    return Path.of(value)
}
