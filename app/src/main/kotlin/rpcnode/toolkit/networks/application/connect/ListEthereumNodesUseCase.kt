package rpcnode.toolkit.networks.application.connect

import java.net.URI
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.ethereum.infrastructure.EthereumPortTable
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

/**
 * Active (or filtered) Ethereum nodes for L2 Start pickers, plus the env's
 * `publicTip` RPC/beacon from `chains/ethereum/network.yml`.
 */
class ListEthereumNodesUseCase(
    private val facts: NetworkFactsRepository,
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
)
{
    data class PublicEndpoint(
        val label: String,
        val rpc: String,
        val beacon: String,
    )

    data class Item(
        val id: String,
        val name: String,
        val env: String,
        val status: String,
        val serverId: String,
        val sameHost: Boolean,
        val rpc: String,
        val beacon: String,
        /** Catalog `publicTip.urls` for this env (shown next to the node). */
        val publicEndpoint: String?,
    )

    data class Ok(
        val env: String,
        val public: PublicEndpoint?,
        val items: List<Item>,
    )

    sealed interface Result
    {
        data class Ready(val value: Ok) : Result
        data object UnknownEnv : Result
    }

    suspend operator fun invoke(
        envRaw: String,
        statusRaw: String = "active",
        serverId: String? = null,
    ): Result
    {
        val ethFacts = facts.factsFor(NetworkId.ETHEREUM) ?: return Result.UnknownEnv
        val env = envRaw.trim().lowercase()
        val envFacts = ethFacts.envs.firstOrNull { it.id == env } ?: return Result.UnknownEnv
        val wantedStatus = parseStatusFilter(statusRaw)
        val ports = EthereumPortTable.forEnv(env)
        val publicRpc = envFacts.publicTipUrls.firstOrNull()?.trim().orEmpty()
        val publicBeacon = envFacts.publicTipBeaconUrls.firstOrNull()?.trim().orEmpty()
            .ifEmpty { envFacts.l1BeaconUrl?.trim().orEmpty() }
        val public = if (publicRpc.isNotEmpty())
        {
            PublicEndpoint(
                label = "Public · $publicRpc",
                rpc = publicRpc,
                beacon = publicBeacon,
            )
        }
        else
        {
            null
        }

        val serverById = servers.list().associateBy { it.id.value }
        val items = nodes.list()
            .filter { it.network.value == NetworkId.ETHEREUM.value }
            .filter { it.env.value == env }
            .filter { wantedStatus.isEmpty() || it.status.value in wantedStatus }
            .sortedWith(
                compareByDescending<Node> {
                    serverId != null && it.serverId.value == serverId
                }.thenBy { it.name.lowercase() },
            )
            .mapNotNull { node ->
                val sameHost = serverId != null && node.serverId.value == serverId
                val host = if (sameHost)
                {
                    "127.0.0.1"
                }
                else
                {
                    hostFromAgentUrl(serverById[node.serverId.value]?.agentUrl.orEmpty())
                        ?: return@mapNotNull null
                }
                Item(
                    id = node.id.value,
                    name = node.name.ifBlank { "ethereum ${node.env.value}" },
                    env = node.env.value,
                    status = node.status.value,
                    serverId = node.serverId.value,
                    sameHost = sameHost,
                    rpc = "http://$host:${ports.http}",
                    beacon = "http://$host:${ports.beacon}",
                    publicEndpoint = publicRpc.ifEmpty { null },
                )
            }

        return Result.Ready(Ok(env = env, public = public, items = items))
    }

    companion object
    {
        fun parseStatusFilter(raw: String): Set<String>
        {
            val parts = raw.split(',', ' ', ';')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
            if (parts.isEmpty() || parts.contains("all"))
            {
                return emptySet()
            }
            return parts.map { NodeStatus.parse(it).value }.toSet()
        }

        fun hostFromAgentUrl(agentUrl: String): String?
        {
            val raw = agentUrl.trim()
            if (raw.isEmpty())
            {
                return null
            }
            return runCatching {
                URI(raw).host?.trim()?.takeIf { it.isNotEmpty() }
            }.getOrNull()
        }

        /** Base/Arb env → ethereum L1 env. */
        fun l1EnvForChild(childNetwork: String, childEnv: String): String?
        {
            return when (childNetwork.trim().lowercase())
            {
                "base", "arb" ->
                {
                    val e = childEnv.trim().lowercase()
                    if (e == "sepolia" || e == "testnet") "sepolia" else "mainnet"
                }
                else -> null
            }
        }
    }
}
