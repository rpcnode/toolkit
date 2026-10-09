package rpcnode.toolkit.networks.application.connect

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

/**
 * Start-step L1 parent choices for Base / Arb: ethereum `publicTip` + active
 * Ethereum nodes (via [ListEthereumNodesUseCase]).
 */
class ListL1ParentChoicesUseCase(
    private val facts: NetworkFactsRepository,
    private val ethereumNodes: ListEthereumNodesUseCase,
)
{
    constructor(
        facts: NetworkFactsRepository,
        nodes: NodeRepository,
        servers: ServerRepository,
    ) : this(
        facts = facts,
        ethereumNodes = ListEthereumNodesUseCase(facts, nodes, servers),
    )

    data class Choice(
        val id: String,
        val kind: String,
        val label: String,
        val rpc: String,
        val beacon: String,
        val status: String? = null,
        val sameHost: Boolean = false,
        val nodeId: String? = null,
        val serverId: String? = null,
    )

    data class Ok(
        val l1Env: String,
        val pickHelp: String?,
        val choices: List<Choice>,
    )

    sealed interface Result
    {
        data class Ready(val value: Ok) : Result
        data object NotApplicable : Result
        data object UnknownNetwork : Result
        data object UnknownEnv : Result
    }

    suspend operator fun invoke(
        forNetwork: String,
        env: String,
        serverId: String?,
    ): Result
    {
        val networkId = NetworkId.parse(forNetwork.trim()) ?: return Result.UnknownNetwork
        val networkFacts = facts.factsFor(networkId) ?: return Result.UnknownNetwork
        val envId = env.trim().lowercase()
        if (envId.isEmpty() || networkFacts.envs.none { it.id == envId })
        {
            return Result.UnknownEnv
        }
        val l1Env = ListEthereumNodesUseCase.l1EnvForChild(networkId.value, envId)
            ?: return Result.NotApplicable
        val pickHelp = networkFacts.envs.single { it.id == envId }.l1PickHelp
        val eth = when (val listed = ethereumNodes(envRaw = l1Env, statusRaw = "active", serverId = serverId))
        {
            is ListEthereumNodesUseCase.Result.Ready -> listed.value
            ListEthereumNodesUseCase.Result.UnknownEnv -> return Result.UnknownEnv
        }

        val choices = mutableListOf<Choice>()
        val public = eth.public
        if (public != null && public.rpc.isNotEmpty())
        {
            choices += Choice(
                id = "public",
                kind = "public",
                label = public.label,
                rpc = public.rpc,
                beacon = public.beacon,
            )
        }

        for (node in eth.items)
        {
            val where = if (node.sameHost) "this host" else node.rpc.substringAfter("://").substringBefore('/')
            choices += Choice(
                id = "node:${node.id}",
                kind = "node",
                label = "${node.name} · ${node.env} · $where · ${node.status}",
                rpc = node.rpc,
                beacon = node.beacon,
                status = node.status,
                sameHost = node.sameHost,
                nodeId = node.id,
                serverId = node.serverId,
            )
        }

        return Result.Ready(
            Ok(
                l1Env = l1Env,
                pickHelp = pickHelp,
                choices = choices,
            ),
        )
    }
}
