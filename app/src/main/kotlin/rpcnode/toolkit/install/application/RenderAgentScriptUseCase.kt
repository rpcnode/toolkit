package rpcnode.toolkit.install.application

import java.nio.file.Files
import java.nio.file.Path

/** Version + staged jar for `/install/version` and self-update. Host install: `sudo java -jar rpcnode-agent.jar install`. */
class RenderAgentScriptUseCase(
    private val installDir: Path,
    private val agentVersion: String = classpathAgentVersion(),
)
{
    /** Same `chainAgentVersion` as the agent JAR (`/agent/version` on the classpath). */
    fun version(): String = agentVersion.trim()

    fun jar(): Path?
    {
        val latest = installDir.resolve("binaries").resolve("rpcnode-agent.jar")
        return latest.takeIf { Files.isRegularFile(it) }
    }
}

fun classpathAgentVersion(): String =
    RenderAgentScriptUseCase::class.java.getResourceAsStream("/agent/version")
        ?.bufferedReader()
        ?.use { it.readText().trim() }
        .orEmpty()
