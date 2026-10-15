package rpcnode.toolkit.networks.application.connect

import java.net.URI
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.arb.infrastructure.ArbPortTable
import rpcnode.toolkit.chains.ethereum.infrastructure.EthereumPortTable
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

/**
 * Active (or filtered) Ethereum nodes for L2 Start pickers, plus the env's
 * `publicTip` RPC/beacon from `chains/ethereum/network.yml`.
 *
 * [parent] = [NetworkId.ARB] lists Arbitrum One nodes instead (parent chain of an Orbit L3 such as Xai): no beacon exists
 * there, so every beacon field is empty.
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
        parent: NetworkId = NetworkId.ETHEREUM,
    ): Result
    {
        if (parent != NetworkId.ETHEREUM && parent != NetworkId.ARB)
        {
            return Result.UnknownEnv
        }
        val ethFacts = facts.factsFor(parent) ?: return Result.UnknownEnv
        val env = envRaw.trim().lowercase()
        val envFacts = ethFacts.envs.firstOrNull { it.id == env } ?: return Result.UnknownEnv
        val wantedStatus = parseStatusFilter(statusRaw)
        val httpPort: Int
        val beaconPort: Int?
        if (parent == NetworkId.ARB)
        {
            httpPort = ArbPortTable.forEnv(env).http
            beaconPort = null
        }
        else
        {
            val ports = EthereumPortTable.forEnv(env)
            httpPort = ports.http
            beaconPort = ports.beacon
        }
        val publicRpc = envFacts.publicTipUrls.firstOrNull()?.trim().orEmpty()
        // an Arbitrum One parent has no beacon (the `l1Parent.beacon` of `arb` is ITS Ethereum parent's)
        val publicBeacon = if (parent == NetworkId.ARB)
        {
            ""
        }
        else
        {
            envFacts.publicTipBeaconUrls.firstOrNull()?.trim().orEmpty()
                .ifEmpty { envFacts.l1BeaconUrl?.trim().orEmpty() }
        }
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
            .filter { it.network.value == parent.value }
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
                    name = node.name.ifBlank { "${parent.value} ${node.env.value}" },
                    env = node.env.value,
                    status = node.status.value,
                    serverId = node.serverId.value,
                    sameHost = sameHost,
                    rpc = "http://$host:$httpPort",
                    beacon = if (beaconPort != null) "http://$host:$beaconPort" else "",
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

        /** Network whose nodes a child's parent picker lists: Arbitrum One for Xai, Ethereum for everything else. */
        fun parentNetworkFor(childNetwork: String): NetworkId =
            if (childNetwork.trim().lowercase() == "xai") NetworkId.ARB else NetworkId.ETHEREUM

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

        /** Base/Arb env → ethereum L1 env; Xai (an L3) → the `mainnet` Arbitrum One node env (see [parentNetworkFor]). */
        fun l1EnvForChild(childNetwork: String, childEnv: String): String?
        {
            return when (childNetwork.trim().lowercase())
            {
                "xai" -> "mainnet"
                "base", "arb", "fuel" ->
                {
                    val e = childEnv.trim().lowercase()
                    if (e == "sepolia" || e == "testnet") "sepolia" else "mainnet"
                }
                else -> null
            }
        }
    }
}
