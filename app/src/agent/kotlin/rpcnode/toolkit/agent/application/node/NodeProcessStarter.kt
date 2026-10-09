package rpcnode.toolkit.agent.application.node

import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/** HTTP / agent-local copy of the panel launch plan. */
data class NodeLaunchPlan(
    val kind: String,
    val entry: String,
    val args: List<String> = emptyList(),
    val extractArchiveGlob: String? = null,
    val normalizeDir: String? = null,
    val javaMajor: Int? = null,
    val logFile: String? = null,
)
{
    fun toSpec() = NodeLaunchSpec(
        kind = kind,
        entry = entry,
        args = args,
        extractArchiveGlob = extractArchiveGlob,
        normalizeDir = normalizeDir,
        javaMajor = javaMajor,
        logFile = logFile,
    )
}

data class NodeHeightPlan(
    val kind: String,
    val portRole: String = "",
)
{
    fun toSpec() = NodeHeightSpec(kind = kind, portRole = portRole)
}

data class NodeStartCommand(
    val nodeId: String,
    val network: String,
    val env: String,
    val nodeDir: String,
    val configFile: String?,
    val httpPort: Int,
    val program: String = "",
    /** Known client pin from the panel; host also reads `{nodeDir}/VERSION` as fallback. */
    val clientVersion: String = "",
    val launch: NodeLaunchPlan,
    val height: NodeHeightPlan,
)

sealed interface NodeStartProcessResult
{
    data class Started(val pid: Long, val alreadyRunning: Boolean = false) : NodeStartProcessResult
    data object InvalidNodeDir : NodeStartProcessResult
    data object InvalidLaunch : NodeStartProcessResult
    data object UnsupportedNetwork : NodeStartProcessResult
    /** systemd node units require a root agent. */
    data object NotRoot : NodeStartProcessResult
    data class Failed(val detail: String) : NodeStartProcessResult
    /** Host is preparing the client binary; Start again when ready. */
    data class Pending(val detail: String) : NodeStartProcessResult
}

/** One network's host runtime — chain types from main `chains/<id>`. */
data class ChainNodeRuntime(
    val network: String,
    val starter: HostNodeProcessStarter,
    val height: HostNodeHeightProbe,
)
