package rpcnode.toolkit.agent.application.status

import rpcnode.toolkit.agent.domain.model.AgentIdentity

class GetAgentIdentityUseCase(
    private val version: String,
    private val port: Int,
)
{
    operator fun invoke(): AgentIdentity
    {
        val os = System.getProperty("os.name")?.lowercase().orEmpty().let { raw ->
            when
            {
                raw.contains("linux") -> "linux"
                raw.contains("mac") -> "darwin"
                raw.contains("win") -> "windows"
                else -> raw.ifBlank { "unknown" }
            }
        }
        val arch = System.getProperty("os.arch")?.lowercase().orEmpty().let { raw ->
            when
            {
                raw.contains("aarch64") || raw.contains("arm64") -> "arm64"
                raw.contains("amd64") || raw.contains("x86_64") -> "amd64"
                else -> raw.ifBlank { "unknown" }
            }
        }
        return AgentIdentity(version = version, os = os, arch = arch, port = port)
    }
}
