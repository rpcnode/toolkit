package rpcnode.toolkit.servers.application.probe

data class HostAgentIdentity(
    val version: String,
    val os: String,
    val arch: String,
    val osPretty: String,
)

sealed interface HostAgentLookup
{
    data class Ok(val identity: HostAgentIdentity) : HostAgentLookup
    data class Unauthorized(val status: Int) : HostAgentLookup
    data class Failed(val detail: String) : HostAgentLookup
}

fun interface HostAgentClient
{
    /** GET /api/v1/agent on the host, authorized with the installer-generated token. */
    suspend fun identity(agentUrl: String, token: String): HostAgentIdentity?

    suspend fun lookup(agentUrl: String, token: String): HostAgentLookup
    {
        val got = identity(agentUrl, token)
        return if (got != null) HostAgentLookup.Ok(got) else HostAgentLookup.Failed("no response")
    }
}

fun interface EnrollHostAgent
{
    /** POST /api/v1/enroll — agent pings the panel, then stores URL + server id. */
    suspend fun enroll(agentUrl: String, token: String, panelUrl: String, serverId: String): EnrollHostAgentResult
}

sealed interface EnrollHostAgentResult
{
    data object Ok : EnrollHostAgentResult
    data class Failed(val detail: String = "") : EnrollHostAgentResult
    data object PanelUnreachable : EnrollHostAgentResult
    /** Agent answered but rejected the token (HTTP 401/403). */
    data object Unauthorized : EnrollHostAgentResult
}

fun interface UnenrollHostAgent
{
    /** POST /api/v1/unenroll — agent drops panel enrollment and stops pushing metrics. */
    suspend fun unenroll(agentUrl: String, token: String): Boolean
}

data class AgentPortCheck(
    val port: Int,
    val free: Boolean,
    val holder: String? = null,
)

fun interface CheckAgentPorts
{
    /** POST /api/v1/agent/ports/check on the host. Null means the agent could not be reached. */
    suspend fun checkPorts(agentUrl: String, token: String, ports: List<Int>): List<AgentPortCheck>?
}

data class HostAgentUpdate(
    val ok: Boolean,
    val updated: Boolean,
    val version: String,
    val remoteVersion: String,
    val message: String,
    val error: String = "",
    val status: Int,
)

fun interface UpdateHostAgent
{
    /** POST /api/v1/agent/update — host downloads the panel jar and restarts. */
    suspend fun update(agentUrl: String, token: String, force: Boolean): HostAgentUpdate?
}

const val AGENT_METRICS_INGEST_PATH = "/api/agent/v1/metrics"
