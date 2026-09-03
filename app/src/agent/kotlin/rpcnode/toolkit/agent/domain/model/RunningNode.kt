package rpcnode.toolkit.agent.domain.model

/** One node process the agent started and keeps alive for height reporting / stop. */
data class RunningNode(
    val nodeId: String,
    val network: String,
    val env: String,
    val nodeDir: String,
    val httpPort: Int,
    val pid: Long,
    val configFile: String = "",
    val program: String = "",
    val heightKind: String = "",
    /** Relative to [nodeDir], e.g. `logs/tron.log`. Empty → `logs/node.out`. */
    val logFile: String = "",
    /** Chain client version from panel start plan and/or `{nodeDir}/VERSION`. */
    val clientVersion: String = "",
)
