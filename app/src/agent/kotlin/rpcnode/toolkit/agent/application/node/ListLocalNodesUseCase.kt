package rpcnode.toolkit.agent.application.node

/**
 * One network/env already provisioned on this host (systemd / Go tip / agent registry).
 * Panel discover filters against catalog and existing workloads.
 */
data class LocalInstalledNode(
    val network: String,
    val env: String,
    val status: String = "present",
    val source: String = "",
    val publicPort: Int = 0,
    val agentPort: Int = 0,
    val nodeHttpPort: Int = 0,
    val p2pPort: Int = 0,
)

/** Scan this host for installed fullnodes (Add Server → Existing nodes). */
fun interface ListLocalInstalledNodes
{
    operator fun invoke(): List<LocalInstalledNode>
}

class ListLocalNodesUseCase(
    private val inventory: ListLocalInstalledNodes,
)
{
    operator fun invoke(): List<LocalInstalledNode> = inventory()
}
