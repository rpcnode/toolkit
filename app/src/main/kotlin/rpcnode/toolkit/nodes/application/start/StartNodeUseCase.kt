package rpcnode.toolkit.nodes.application.start

import java.time.Instant
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.application.config.clientConfigTemplateName
import rpcnode.toolkit.nodes.application.options.SaveNodeInstallOptionsResult
import rpcnode.toolkit.nodes.application.options.SaveNodeInstallOptionsUseCase
import rpcnode.toolkit.nodes.application.snapshot.ResolveSnapshotDestDirUseCase
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

sealed interface StartNodeResult
{
    data class Started(
        val nodeId: String,
        val path: String,
        val pid: Long,
        val status: String,
        val alreadyRunning: Boolean,
        val installOptionsJson: String,
        val files: List<String> = emptyList(),
    ) : StartNodeResult

    data object NotFound : StartNodeResult
    data object ServerNotFound : StartNodeResult
    data object NoClientConfig : StartNodeResult
    data object NoDiskLayout : StartNodeResult
    data object TemplateMissing : StartNodeResult
    data object InvalidType : StartNodeResult
    data object UnsupportedNetwork : StartNodeResult
    data class AgentUnreachable(val detail: String = "") : StartNodeResult
    data class SyncFailed(val error: String, val message: String) : StartNodeResult
    data class StartFailed(val error: String, val message: String) : StartNodeResult
    /** Host is building the client binary in the background — press Start again when ready. */
    data class BuildPending(val error: String, val message: String) : StartNodeResult
}

/**
 * Wizard Start: launch the chain process only.
 * Client files must already be on the host ([ApplyNodeClientConfigUseCase] after Disks).
 * Saves Start-step install_options, then [StartNodeOnHost] with the [ChainNodeStart] recipe.
 */
class StartNodeUseCase(
    private val saveInstallOptions: SaveNodeInstallOptionsUseCase,
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val facts: NetworkFactsRepository,
    private val catalog: ClientProgramCatalog,
    private val clients: ClientVersionRepository,
    private val resolveDestDir: ResolveSnapshotDestDirUseCase,
    private val startOnHost: StartNodeOnHost,
    private val chainStarts: Map<NetworkId, ChainNodeStart>,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    suspend operator fun invoke(
        idRaw: String,
        installOptionsJson: String?,
    ): StartNodeResult
    {
        when (val saved = saveInstallOptions(idRaw, snapshotType = null, installOptionsJson))
        {
            is SaveNodeInstallOptionsResult.Saved -> Unit
            SaveNodeInstallOptionsResult.NotFound -> return StartNodeResult.NotFound
            SaveNodeInstallOptionsResult.InvalidType -> return StartNodeResult.InvalidType
        }

        val id = NodeId.parse(idRaw.trim()) ?: return StartNodeResult.NotFound
        val node = nodes.findById(id) ?: return StartNodeResult.NotFound
        val server = servers.find(node.serverId) ?: return StartNodeResult.ServerNotFound
        val networkFacts = facts.factsFor(node.network) ?: return StartNodeResult.NoClientConfig
        val clientConfig = networkFacts.clientConfig ?: return StartNodeResult.NoClientConfig
        val nodeDir = resolveDestDir(node)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return StartNodeResult.NoDiskLayout
        val flagsOnly = clientConfig.format.trim().lowercase() == "flags"
        val templateName = if (flagsOnly)
        {
            null
        }
        else
        {
            clientConfigTemplateName(clientConfig, node.env.value)
                ?: return StartNodeResult.TemplateMissing
        }

        val chainStart = chainStarts[node.network] ?: return StartNodeResult.UnsupportedNetwork
        val programSpec = catalog.programsFor(node.network, node.env)
            .firstOrNull { it.programId.equals(clientConfig.program, ignoreCase = true) }
        val plan = chainStart.plan(
            ChainNodeStartContext(
                network = node.network,
                env = node.env.value,
                program = clientConfig.program,
                configFile = templateName,
                nodeDir = nodeDir,
                javaMajor = programSpec?.requirements?.javaMajor,
                logFile = programSpec?.requirements?.logFile,
                installOptionsJson = node.installOptionsJson,
                diskLayoutJson = node.diskLayoutJson,
            ),
        )

        val ports = catalog.programsFor(node.network, node.env).flatMap { it.ports }
        val httpPort = ports
            .firstOrNull { it.role.equals(plan.height.portRole, ignoreCase = true) }
            ?.port
            ?: node.nodeHttpPort.takeIf { it > 0 }
            ?: 0

        if (server.agentUrl.isBlank() || server.agentKey.isBlank())
        {
            return StartNodeResult.AgentUnreachable("missing agent url or key")
        }

        val started = startOnHost.start(
            server.agentUrl,
            server.agentKey,
            StartNodeOnHostCommand(
                nodeId = node.id.value,
                network = node.network.value,
                env = node.env.value,
                nodeDir = nodeDir,
                configFile = templateName,
                httpPort = httpPort,
                program = clientConfig.program,
                clientVersion = clients.find(node.network, node.env, clientConfig.program)
                    ?.currentVersion
                    .orEmpty(),
                launch = plan.launch,
                height = plan.height,
            ),
        ) ?: return StartNodeResult.AgentUnreachable()

        return when (started)
        {
            is StartNodeOnHostResult.Ok ->
            {
                val now = clock()
                nodes.updateStatus(id, NodeStatus.SYNC, now)
                val pin = clients.find(node.network, node.env, clientConfig.program)
                val clientVersion = pin?.currentVersion.orEmpty()
                if (clientVersion.isNotEmpty())
                {
                    val latest = pin?.latestVersion?.trim()?.ifEmpty { null }
                        ?: clientVersion
                    nodes.updateClientVersion(
                        id = id,
                        clientVersion = clientVersion,
                        clientLatest = latest,
                        clientUpdateAvailable = false,
                        updatedAt = now,
                    )
                }
                StartNodeResult.Started(
                    nodeId = node.id.value,
                    path = nodeDir,
                    pid = started.pid,
                    status = NodeStatus.SYNC.value,
                    alreadyRunning = started.alreadyRunning,
                    installOptionsJson = node.installOptionsJson,
                )
            }
            is StartNodeOnHostResult.Failed ->
            {
                if (started.error == "unsupported_network")
                {
                    StartNodeResult.UnsupportedNetwork
                }
                else
                {
                    StartNodeResult.StartFailed(error = started.error, message = started.message)
                }
            }
            is StartNodeOnHostResult.Pending ->
                StartNodeResult.BuildPending(error = started.error, message = started.message)
        }
    }
}
