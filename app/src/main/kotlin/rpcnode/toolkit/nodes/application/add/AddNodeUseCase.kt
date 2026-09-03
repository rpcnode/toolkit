package rpcnode.toolkit.nodes.application.add

import java.time.Clock
import java.time.Instant
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkCatalog
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.nodes.domain.repository.NodeInsertResult
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.nodes.application.ingest.looseSameVersion
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.repository.ServerRepository

sealed interface AddNodeResult
{
    data class Created(val node: Node) : AddNodeResult
    data object ServerIdRequired : AddNodeResult
    data object NetworkRequired : AddNodeResult
    data object EnvRequired : AddNodeResult
    data object UnknownNetwork : AddNodeResult
    data object UnknownEnv : AddNodeResult
    data object ServerNotFound : AddNodeResult
    data object NoClient : AddNodeResult
    data class AlreadyExists(val existing: Node) : AddNodeResult
    data class OneEnvPerHost(val occupied: Node) : AddNodeResult
    data object InsertFailed : AddNodeResult
}

/**
 * Registers a node row after network + env + server. Ports / install come later —
 * status is [NodeStatus.AWAITING_PORTS].
 */
class AddNodeUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val catalog: NetworkCatalog,
    private val clients: ClientVersionRepository,
    private val facts: NetworkFactsRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> NodeId = { NodeId.generate() },
)
{
    suspend operator fun invoke(
        serverIdRaw: String,
        networkRaw: String,
        envRaw: String,
        nameRaw: String? = null,
    ): AddNodeResult
    {
        val serverId = ServerId.parse(serverIdRaw) ?: return AddNodeResult.ServerIdRequired
        if (networkRaw.trim().isEmpty())
        {
            return AddNodeResult.NetworkRequired
        }
        if (envRaw.trim().isEmpty())
        {
            return AddNodeResult.EnvRequired
        }
        val networkId = NetworkId.parse(networkRaw) ?: return AddNodeResult.UnknownNetwork
        val chain = catalog.find(networkId) ?: return AddNodeResult.UnknownNetwork
        val env = chain.env(envRaw) ?: return AddNodeResult.UnknownEnv
        val server = servers.find(serverId) ?: return AddNodeResult.ServerNotFound
        if (!server.isActive())
        {
            return AddNodeResult.ServerNotFound
        }
        if (!hasClient(chain.id, env.id))
        {
            return AddNodeResult.NoClient
        }
        nodes.findByServerNetworkEnv(serverId, chain.id, env.id)?.let { return AddNodeResult.AlreadyExists(it) }
        if (facts.factsFor(chain.id)?.oneEnvPerHost == true)
        {
            val other = nodes.listOnServer(serverId).firstOrNull { it.network == chain.id && it.env != env.id }
            if (other != null)
            {
                return AddNodeResult.OneEnvPerHost(other)
            }
        }
        val now = Instant.now(clock).toString()
        val name = nameRaw?.trim().orEmpty().ifEmpty { Node.defaultName(chain.displayLabel(), env.id) }
        val clientFields = clientVersionAtAdd(chain.id, env.id)
        val node = Node(
            id = newId(),
            serverId = serverId,
            name = name,
            network = chain.id,
            env = env.id,
            status = NodeStatus.AWAITING_PORTS,
            clientVersion = clientFields.clientVersion,
            clientLatest = clientFields.clientLatest,
            clientUpdateAvailable = clientFields.clientUpdateAvailable,
            createdAt = now,
            updatedAt = now,
        )
        return when (nodes.insert(node))
        {
            NodeInsertResult.Ok ->
                AddNodeResult.Created(node)
            NodeInsertResult.Duplicate ->
            {
                val existing = nodes.findByServerNetworkEnv(serverId, chain.id, env.id)
                if (existing != null)
                {
                    AddNodeResult.AlreadyExists(existing)
                }
                else
                {
                    AddNodeResult.InsertFailed
                }
            }
        }
    }

    private suspend fun hasClient(network: NetworkId, env: EnvId): Boolean =
        clientVersionAtAdd(network, env).clientVersion.isNotBlank()

    private suspend fun clientVersionAtAdd(network: NetworkId, env: EnvId): ClientVersionFields
    {
        val pin = clientPin(network, env) ?: return ClientVersionFields()
        val clientVersion = pin.currentVersion.trim()
        if (clientVersion.isEmpty())
        {
            return ClientVersionFields()
        }
        val clientLatest = pin.latestVersion.trim().ifEmpty { clientVersion }
        val clientUpdateAvailable = clientLatest.isNotEmpty() && !looseSameVersion(clientVersion, clientLatest)
        return ClientVersionFields(
            clientVersion = clientVersion,
            clientLatest = clientLatest,
            clientUpdateAvailable = clientUpdateAvailable,
        )
    }

    private suspend fun clientPin(network: NetworkId, env: EnvId) =
        facts.factsFor(network)?.clientConfig?.program?.trim()?.takeIf { it.isNotEmpty() }?.let { program ->
            clients.find(network, env, program)
        }
            ?: clients.list().firstOrNull { it.network == network && it.env == env && it.currentVersion.isNotBlank() }

    private data class ClientVersionFields(
        val clientVersion: String = "",
        val clientLatest: String = "",
        val clientUpdateAvailable: Boolean = false,
    )
}
