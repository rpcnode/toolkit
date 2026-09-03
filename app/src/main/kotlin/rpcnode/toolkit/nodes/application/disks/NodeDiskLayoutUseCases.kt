package rpcnode.toolkit.nodes.application.disks

import rpcnode.toolkit.networks.domain.model.ClientConfigFacts
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.domain.model.DiskRoleDef
import rpcnode.toolkit.nodes.domain.model.NodeDiskLayout
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository

sealed interface NodeDiskLayoutResult
{
    data class Ok(
        val nodeId: String,
        val network: String,
        val env: String,
        val diskLayout: NodeDiskLayout?,
        val installOptionsJson: String,
        val multiDiskRoles: List<DiskRoleDef>,
        val layoutRules: List<String>,
        val recommended: NodeDiskLayout?,
        val clientConfig: ClientConfigFacts? = null,
    ) : NodeDiskLayoutResult

    data object NotFound : NodeDiskLayoutResult
}

class GetNodeDiskLayoutUseCase(
    private val nodes: NodeRepository,
    private val facts: NetworkFactsRepository,
    private val hostDisks: GetHostDisksUseCase,
)
{
    suspend operator fun invoke(id: String): NodeDiskLayoutResult
    {
        val nodeId = NodeId.parse(id.trim()) ?: return NodeDiskLayoutResult.NotFound
        val node = nodes.findById(nodeId) ?: return NodeDiskLayoutResult.NotFound
        val network = node.network.value
        val env = node.env.value
        val networkFacts = facts.factsFor(node.network)
        val envFacts = networkFacts?.envs?.firstOrNull { it.id == env }
        val catalogRoles = NetworkDiskLayoutCatalog.diskRoles(networkFacts, envFacts)
        val layoutRules = NetworkDiskLayoutCatalog.layoutRules(networkFacts)

        val hostCatalog = when (val hostResult = hostDisks(node.serverId.value))
        {
            is HostDisksResult.Ok -> hostResult.catalog
            else -> null
        }
        val recommended = hostCatalog?.let { recommendDiskLayout(it, catalogRoles, network, env) }

        val saved = decodeNodeDiskLayout(node.diskLayoutJson)
        val diskLayout = when
        {
            saved != null && saved.placementsFromCompatFields().any { it.mount.isNotBlank() || it.dir.isNotBlank() } ->
                enrichDiskLayout(saved, catalogRoles, network, env)
            saved != null && catalogRoles.isNotEmpty() ->
                enrichDiskLayout(saved, catalogRoles, network, env)
            recommended != null ->
                enrichDiskLayout(recommended, catalogRoles, network, env)
            catalogRoles.isNotEmpty() ->
                emptyDiskLayout(catalogRoles, network, env)
            else -> saved
        }

        return NodeDiskLayoutResult.Ok(
            nodeId = node.id.value,
            network = network,
            env = env,
            diskLayout = diskLayout,
            installOptionsJson = node.installOptionsJson,
            multiDiskRoles = catalogRoles,
            layoutRules = layoutRules,
            recommended = recommended?.let { enrichDiskLayout(it, catalogRoles, network, env) },
            clientConfig = networkFacts?.clientConfig,
        )
    }
}

sealed interface SaveNodeDiskLayoutResult
{
    data class Saved(val nodeId: String) : SaveNodeDiskLayoutResult
    data object NotFound : SaveNodeDiskLayoutResult
}

class SaveNodeDiskLayoutUseCase(
    private val nodes: NodeRepository,
    private val clock: () -> String = { java.time.Instant.now().toString() },
)
{
    suspend operator fun invoke(id: String, diskLayoutJson: String): SaveNodeDiskLayoutResult
    {
        val nodeId = NodeId.parse(id.trim()) ?: return SaveNodeDiskLayoutResult.NotFound
        val node = nodes.findById(nodeId) ?: return SaveNodeDiskLayoutResult.NotFound
        val ok = nodes.saveDiskLayout(nodeId, diskLayoutJson, clock())
        return if (ok || node.diskLayoutJson == diskLayoutJson)
        {
            SaveNodeDiskLayoutResult.Saved(node.id.value)
        }
        else
        {
            SaveNodeDiskLayoutResult.NotFound
        }
    }
}
