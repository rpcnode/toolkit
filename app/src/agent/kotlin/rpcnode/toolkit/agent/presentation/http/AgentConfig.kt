package rpcnode.toolkit.agent.presentation.http

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.agent.domain.model.AGENT_API_PORT

/** Token lives in a file. The installer (or IDEA) passes the path via AGENT_TOKEN_FILE. */
data class AgentConfig(
    val listen: String = System.getenv("AGENT_LISTEN") ?: "0.0.0.0",
    val port: Int = System.getenv("AGENT_PORT")?.toIntOrNull() ?: DEFAULT_PORT,
    val tokenFile: Path,
    val token: String = tokenFromFile(tokenFile),
    val version: String = versionFromGradle(),
    /** IDEA run configs set `RPCNODE_DEV=1`: HTTP call log, DEBUG, print token. */
    val dev: Boolean = rpcnodeDev(),
)
{
    companion object
    {
        /** Not Go tip :38990 — that port is already the rpcnode-api-agent. */
        const val DEFAULT_PORT = AGENT_API_PORT

        /**
         * `AGENT_TOKEN_FILE` wins. Otherwise `<configDir>/agent.token`
         * (config dir from [rpcnode.toolkit.agent.domain.AgentDirectories]).
         */
        fun defaultTokenFile(
            tokenFileEnv: String? = System.getenv("AGENT_TOKEN_FILE"),
            configDir: Path,
        ): Path
        {
            val fromEnv = tokenFileEnv?.trim()?.ifEmpty { null }
            if (fromEnv != null)
            {
                return Path.of(fromEnv)
            }
            return configDir.resolve("agent.token")
        }
    }
}

fun rpcnodeDev(raw: String? = System.getenv("RPCNODE_DEV")): Boolean
{
    val v = raw?.trim()?.lowercase() ?: return false
    return v == "1" || v == "true" || v == "yes"
}

internal fun tokenFromFile(path: Path): String
{
    if (!Files.isRegularFile(path))
    {
        return ""
    }
    return Files.readString(path).trim()
}

internal fun versionFromGradle(): String
{
    val fromEnv = System.getenv("RPCNODE_AGENT_VERSION")?.trim().orEmpty()
        .ifEmpty { System.getenv("CHAIN_AGENT_VERSION")?.trim().orEmpty() }
    if (fromEnv.isNotEmpty())
    {
        return fromEnv
    }
    return AgentConfig::class.java.getResourceAsStream("/agent/version")
        ?.bufferedReader()
        ?.use { it.readText().trim() }
        .orEmpty()
        .ifBlank { "0.0.0" }
}
