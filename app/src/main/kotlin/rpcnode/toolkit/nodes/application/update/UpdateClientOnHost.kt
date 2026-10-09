package rpcnode.toolkit.nodes.application.update

import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

data class ClientUpdateOnHostCommand(
    val nodeId: String,
    val network: String,
    val env: String,
    val nodeDir: String,
    val configAssignments: Map<String, String> = emptyMap(),
    val configFormat: String = "hoocon",
    val configFile: String? = null,
    val configIniSection: String? = null,
    val configOmitIniKeys: Set<String> = emptySet(),
    val httpPort: Int = 0,
    val program: String = "",
    val clientVersion: String = "",
    val launch: NodeLaunchSpec,
    val height: NodeHeightSpec,
)

data class ClientUpdateInfo(
    val local: String = "",
    val latest: String = "",
    val previousVersion: String = "",
    val updateAvailable: Boolean = false,
    val phase: String = "idle",
    val step: String = "",
    val detail: String = "",
    val pct: Int = 0,
    val lastError: String = "",
    val logTail: String = "",
    /** Host webhook milestones (stopped / updating / started). */
    val events: List<ClientUpdateEvent> = emptyList(),
)

sealed interface ClientUpdateOnHostResult
{
    data class Accepted(val info: ClientUpdateInfo) : ClientUpdateOnHostResult
    data class Failed(val error: String, val message: String) : ClientUpdateOnHostResult
}

sealed interface ClientUpdateStatusOnHostResult
{
    data class Ok(val info: ClientUpdateInfo) : ClientUpdateStatusOnHostResult
    data class Failed(val error: String, val message: String) : ClientUpdateStatusOnHostResult
}

sealed interface ClientRollbackOnHostResult
{
    data class Ok(val info: ClientUpdateInfo) : ClientRollbackOnHostResult
    data class Failed(val error: String, val message: String) : ClientRollbackOnHostResult
}

interface UpdateClientOnHost
{
    suspend fun update(agentUrl: String, token: String, command: ClientUpdateOnHostCommand): ClientUpdateOnHostResult?
    suspend fun status(
        agentUrl: String,
        token: String,
        nodeId: String,
        network: String = "",
        env: String = "",
    ): ClientUpdateStatusOnHostResult?

    suspend fun rollback(
        agentUrl: String,
        token: String,
        nodeId: String,
        network: String = "",
        env: String = "",
    ): ClientRollbackOnHostResult?
}
