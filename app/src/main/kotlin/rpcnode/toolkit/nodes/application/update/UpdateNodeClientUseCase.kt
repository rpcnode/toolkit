package rpcnode.toolkit.nodes.application.update

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.application.InstallPlan
import rpcnode.toolkit.clients.application.InstallPlanFile
import rpcnode.toolkit.clients.application.InstallPlanWriter
import rpcnode.toolkit.clients.application.downloadone.DownloadClientProgramResult
import rpcnode.toolkit.clients.application.downloadone.DownloadClientProgramUseCase
import rpcnode.toolkit.clients.application.inferArchFromFileName
import rpcnode.toolkit.clients.application.inferLaunch
import rpcnode.toolkit.clients.application.installPlanFilesIncludingVersion
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository
import rpcnode.toolkit.networks.application.snapshot.snapshotTypesFor
import rpcnode.toolkit.networks.domain.model.NetworkPinOnly
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.application.config.clientConfigIniSection
import rpcnode.toolkit.nodes.application.config.clientConfigTemplateName
import rpcnode.toolkit.nodes.application.config.resolveClientConfigAssignments
import rpcnode.toolkit.nodes.application.config.resolveClientConfigOmitIniKeys
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.ingest.compareToLatest
import rpcnode.toolkit.nodes.application.snapshot.ResolveSnapshotDestDirUseCase
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

sealed interface UpdateNodeClientResult
{
    data class Accepted(val nodeId: String, val info: ClientUpdateInfo) : UpdateNodeClientResult
    data object NotFound : UpdateNodeClientResult
    data object ServerNotFound : UpdateNodeClientResult
    data object NoClientConfig : UpdateNodeClientResult
    data object NoDiskLayout : UpdateNodeClientResult
    data object TemplateMissing : UpdateNodeClientResult
    data object UnsupportedNetwork : UpdateNodeClientResult
    data class AgentUnreachable(val detail: String = "") : UpdateNodeClientResult
    data class Failed(val error: String, val message: String) : UpdateNodeClientResult
}

sealed interface GetNodeClientUpdateResult
{
    data class Ok(val nodeId: String, val info: ClientUpdateInfo) : GetNodeClientUpdateResult
    data object NotFound : GetNodeClientUpdateResult
    data object ServerNotFound : GetNodeClientUpdateResult
    data class AgentUnreachable(val detail: String = "") : GetNodeClientUpdateResult
    data class Failed(val error: String, val message: String) : GetNodeClientUpdateResult
}

sealed interface RollbackNodeClientResult
{
    data class Ok(val nodeId: String, val info: ClientUpdateInfo) : RollbackNodeClientResult
    data object NotFound : RollbackNodeClientResult
    data object ServerNotFound : RollbackNodeClientResult
    data class AgentUnreachable(val detail: String = "") : RollbackNodeClientResult
    data class Failed(val error: String, val message: String) : RollbackNodeClientResult
}

/**
 * Ensure the panel has the latest client pin, then ask the host agent to stage/promote/start.
 */
class UpdateNodeClientUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val facts: NetworkFactsRepository,
    private val catalog: ClientProgramCatalog,
    private val clients: ClientVersionRepository,
    private val resolveDestDir: ResolveSnapshotDestDirUseCase,
    private val updateOnHost: UpdateClientOnHost,
    private val installPlanWriter: InstallPlanWriter,
    private val clientsDestDir: Path,
    private val downloadClient: DownloadClientProgramUseCase,
    private val chainStarts: Map<NetworkId, ChainNodeStart>,
    private val progress: ClientUpdateProgressStore,
)
{
    suspend operator fun invoke(idRaw: String): UpdateNodeClientResult
    {
        val prepared = prepare(idRaw) ?: return UpdateNodeClientResult.NotFound
        return when (prepared)
        {
            is Prepare.Err -> prepared.toUpdate()
            is Prepare.Ready ->
            {
                val accepted = updateOnHost.update(
                    prepared.agentUrl,
                    prepared.agentKey,
                    prepared.command,
                ) ?: return UpdateNodeClientResult.AgentUnreachable()
                when (accepted)
                {
                    is ClientUpdateOnHostResult.Accepted ->
                    {
                        progress.put(
                            prepared.nodeId,
                            accepted.info.copy(
                                phase = accepted.info.phase.ifEmpty { "updating" },
                                step = accepted.info.step.ifEmpty { "updating" },
                                detail = accepted.info.detail.ifEmpty { "Client update accepted" },
                            ),
                        )
                        UpdateNodeClientResult.Accepted(prepared.nodeId, accepted.info)
                    }
                    is ClientUpdateOnHostResult.Failed ->
                        UpdateNodeClientResult.Failed(accepted.error, accepted.message)
                }
            }
        }
    }

    private sealed interface Prepare
    {
        data class Ready(
            val nodeId: String,
            val agentUrl: String,
            val agentKey: String,
            val command: ClientUpdateOnHostCommand,
        ) : Prepare

        data class Err(val result: UpdateNodeClientResult) : Prepare
    }

    private fun Prepare.Err.toUpdate(): UpdateNodeClientResult = result

    private suspend fun prepare(idRaw: String): Prepare?
    {
        val id = NodeId.parse(idRaw.trim()) ?: return null
        val node = nodes.findById(id) ?: return Prepare.Err(UpdateNodeClientResult.NotFound)
        val server = servers.find(node.serverId)
            ?: return Prepare.Err(UpdateNodeClientResult.ServerNotFound)
        val networkFacts = facts.factsFor(node.network)
            ?: return Prepare.Err(UpdateNodeClientResult.NoClientConfig)
        val clientConfig = networkFacts.clientConfig
            ?: return Prepare.Err(UpdateNodeClientResult.NoClientConfig)
        val flagsOnly = clientConfig.format.trim().lowercase() == "flags"
        if (!flagsOnly && clientConfig.bindings.isEmpty())
        {
            return Prepare.Err(UpdateNodeClientResult.NoClientConfig)
        }
        val layout = decodeNodeDiskLayout(node.diskLayoutJson)
            ?: return Prepare.Err(UpdateNodeClientResult.NoDiskLayout)
        val nodeDir = resolveDestDir(node)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Prepare.Err(UpdateNodeClientResult.NoDiskLayout)
        val templateName = if (flagsOnly)
        {
            null
        }
        else
        {
            clientConfigTemplateName(clientConfig, node.env.value)
                ?: return Prepare.Err(UpdateNodeClientResult.TemplateMissing)
        }
        val programs = catalog.programsFor(node.network, node.env)
        val pinOnly = NetworkPinOnly.isPinOnly(node.network)
        if (programs.isEmpty() && !pinOnly)
        {
            return Prepare.Err(UpdateNodeClientResult.TemplateMissing)
        }
        for (spec in programs)
        {
            when (val downloaded = downloadClient(spec, force = true))
            {
                is DownloadClientProgramResult.Ok -> Unit
                is DownloadClientProgramResult.Failed ->
                    return Prepare.Err(
                        UpdateNodeClientResult.Failed(error = "client_download", message = downloaded.error),
                    )
            }
        }
        val clientsEnvDir = clientsDestDir.resolve(node.network.value).resolve(node.env.value)
        if (!Files.isDirectory(clientsEnvDir))
        {
            if (!pinOnly)
            {
                return Prepare.Err(UpdateNodeClientResult.TemplateMissing)
            }
            runCatching { Files.createDirectories(clientsEnvDir) }.getOrElse { e ->
                return Prepare.Err(
                    UpdateNodeClientResult.Failed(error = "pin_clients_dir", message = e.message ?: "mkdir failed"),
                )
            }
        }
        ensureInstallPlan(
            dir = clientsEnvDir,
            network = node.network.value,
            env = node.env.value,
            program = clientConfig.program.ifBlank { programs.firstOrNull()?.programId.orEmpty() },
        )
        val ports = programs.flatMap { it.ports }
        val snapshotTypes = snapshotTypesFor(facts, node.network, node.env.value)
        val assignments = resolveClientConfigAssignments(
            config = clientConfig,
            layout = layout,
            ports = ports,
            installOptionsJson = node.installOptionsJson,
            snapshotTypes = snapshotTypes,
        )
        val patchAssignments = if (flagsOnly)
        {
            assignments.filter { (_, value) -> value.trim().startsWith("/") }
        }
        else
        {
            assignments
        }
        val omitIniKeys = if (flagsOnly)
        {
            emptySet()
        }
        else
        {
            resolveClientConfigOmitIniKeys(clientConfig, assignments, ports)
        }
        val chainStart = chainStarts[node.network]
            ?: return Prepare.Err(UpdateNodeClientResult.UnsupportedNetwork)
        val programSpec = programs.firstOrNull { it.programId.equals(clientConfig.program, ignoreCase = true) }
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
        val httpPort = ports
            .firstOrNull { it.role.equals(plan.height.portRole, ignoreCase = true) }
            ?.port
            ?: node.nodeHttpPort.takeIf { it > 0 }
            ?: 0
        if (server.agentUrl.isBlank() || server.agentKey.isBlank())
        {
            return Prepare.Err(UpdateNodeClientResult.AgentUnreachable("missing agent url or key"))
        }
        val pin = clients.find(node.network, node.env, clientConfig.program)
        // Target install version = Clients pin latest (fallback current after download).
        val clientVersion = pin?.latestVersion.orEmpty().ifEmpty { pin?.currentVersion.orEmpty() }
        return Prepare.Ready(
            nodeId = node.id.value,
            agentUrl = server.agentUrl,
            agentKey = server.agentKey,
            command = ClientUpdateOnHostCommand(
                nodeId = node.id.value,
                network = node.network.value,
                env = node.env.value,
                nodeDir = nodeDir,
                configAssignments = patchAssignments,
                configFormat = clientConfig.format.ifBlank { "hoocon" },
                configFile = templateName,
                configIniSection = clientConfigIniSection(clientConfig, node.env.value),
                configOmitIniKeys = omitIniKeys,
                httpPort = httpPort,
                program = clientConfig.program,
                clientVersion = clientVersion,
                launch = plan.launch,
                height = plan.height,
            ),
        )
    }

    private suspend fun ensureInstallPlan(dir: Path, network: String, env: String, program: String)
    {
        val files = runCatching {
            Files.list(dir).use { stream ->
                stream.iterator().asSequence()
                    .filter { Files.isRegularFile(it) }
                    .map { it.fileName.toString() }
                    .filter { name ->
                        name != "manifest.json" &&
                            name != "VERSION" &&
                            name != "install-plan.yml" &&
                            !name.startsWith(".")
                    }
                    .toList()
            }
        }.getOrDefault(emptyList())
        if (files.isEmpty())
        {
            return
        }
        val planFiles = installPlanFilesIncludingVersion(
            dir,
            files.map { name ->
                val role = when
                {
                    name.endsWith(".conf", ignoreCase = true) || name.endsWith(".ini", ignoreCase = true) -> "config"
                    name.endsWith(".sh", ignoreCase = true) || name.endsWith(".tmpl", ignoreCase = true) -> "script"
                    else -> "artifact"
                }
                InstallPlanFile(role = role, path = name, arch = inferArchFromFileName(name))
            },
        )
        installPlanWriter.write(
            dir,
            InstallPlan(
                network = network,
                env = env,
                program = program,
                files = planFiles,
                launch = inferLaunch(program, planFiles),
            ),
        )
    }
}

class GetNodeClientUpdateUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val updateOnHost: UpdateClientOnHost,
    private val progress: ClientUpdateProgressStore,
    private val clients: ClientVersionRepository,
    private val facts: NetworkFactsRepository,
)
{
    suspend operator fun invoke(idRaw: String): GetNodeClientUpdateResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return GetNodeClientUpdateResult.NotFound
        val node = nodes.findById(id) ?: return GetNodeClientUpdateResult.NotFound
        val pinStatus = compareToLatest(node, node.clientVersion, clients, facts)
        val cached = progress.get(node.id.value)
        if (cached != null && cached.phase.equals("updating", ignoreCase = true))
        {
            return GetNodeClientUpdateResult.Ok(
                node.id.value,
                enrich(cached, node, pinStatus),
            )
        }
        val server = servers.find(node.serverId) ?: return GetNodeClientUpdateResult.ServerNotFound
        if (server.agentUrl.isBlank() || server.agentKey.isBlank())
        {
            if (cached != null)
            {
                return GetNodeClientUpdateResult.Ok(node.id.value, enrich(cached, node, pinStatus))
            }
            return GetNodeClientUpdateResult.AgentUnreachable("missing agent url or key")
        }
        val status = updateOnHost.status(
            server.agentUrl,
            server.agentKey,
            nodeId = node.id.value,
            network = node.network.value,
            env = node.env.value,
        )
        if (status == null)
        {
            if (cached != null)
            {
                return GetNodeClientUpdateResult.Ok(node.id.value, enrich(cached, node, pinStatus))
            }
            return GetNodeClientUpdateResult.AgentUnreachable()
        }
        return when (status)
        {
            is ClientUpdateStatusOnHostResult.Ok ->
            {
                val host = status.info
                val merged = if (cached != null &&
                    (host.phase.equals("idle", ignoreCase = true) || host.step.isEmpty()) &&
                    cached.events.isNotEmpty()
                )
                {
                    host.copy(
                        events = cached.events,
                        local = host.local.ifEmpty { cached.local },
                        latest = host.latest.ifEmpty { cached.latest },
                        previousVersion = host.previousVersion.ifEmpty { cached.previousVersion },
                        phase = cached.phase.ifEmpty { host.phase },
                        step = cached.step.ifEmpty { host.step },
                        detail = host.detail.ifEmpty { cached.detail },
                        pct = if (host.pct > 0) host.pct else cached.pct,
                        lastError = host.lastError.ifEmpty { cached.lastError },
                        logTail = host.logTail.ifEmpty { cached.logTail },
                    )
                }
                else if (cached != null && cached.events.isNotEmpty())
                {
                    host.copy(events = mergeEventLists(cached.events, host.events))
                }
                else
                {
                    host
                }
                GetNodeClientUpdateResult.Ok(node.id.value, enrich(merged, node, pinStatus))
            }
            is ClientUpdateStatusOnHostResult.Failed ->
            {
                if (cached != null)
                {
                    GetNodeClientUpdateResult.Ok(node.id.value, enrich(cached, node, pinStatus))
                }
                else
                {
                    GetNodeClientUpdateResult.Failed(status.error, status.message)
                }
            }
        }
    }

    private fun enrich(
        info: ClientUpdateInfo,
        node: rpcnode.toolkit.nodes.domain.model.Node,
        pinStatus: rpcnode.toolkit.nodes.application.ingest.ClientVersionCompare,
    ): ClientUpdateInfo =
        info.copy(
            local = info.local.ifEmpty { node.clientVersion },
            latest = info.latest.ifEmpty { pinStatus.latest },
            updateAvailable = if (info.phase.equals("updating", ignoreCase = true))
            {
                info.updateAvailable || pinStatus.updateAvailable
            }
            else
            {
                pinStatus.updateAvailable
            },
        )

    private fun mergeEventLists(
        a: List<ClientUpdateEvent>,
        b: List<ClientUpdateEvent>,
    ): List<ClientUpdateEvent>
    {
        if (b.isEmpty()) return a
        if (a.isEmpty()) return b
        val out = a.toMutableList()
        for (ev in b)
        {
            val idx = out.indexOfFirst { it.id.equals(ev.id, ignoreCase = true) }
            if (idx >= 0) out[idx] = ev else out += ev
        }
        return out
    }
}

class RollbackNodeClientUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val updateOnHost: UpdateClientOnHost,
)
{
    suspend operator fun invoke(idRaw: String): RollbackNodeClientResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return RollbackNodeClientResult.NotFound
        val node = nodes.findById(id) ?: return RollbackNodeClientResult.NotFound
        val server = servers.find(node.serverId) ?: return RollbackNodeClientResult.ServerNotFound
        if (server.agentUrl.isBlank() || server.agentKey.isBlank())
        {
            return RollbackNodeClientResult.AgentUnreachable("missing agent url or key")
        }
        val rolled = updateOnHost.rollback(
            server.agentUrl,
            server.agentKey,
            nodeId = node.id.value,
            network = node.network.value,
            env = node.env.value,
        ) ?: return RollbackNodeClientResult.AgentUnreachable()
        return when (rolled)
        {
            is ClientRollbackOnHostResult.Ok ->
            {
                val restored = rolled.info.local.ifEmpty { rolled.info.previousVersion }
                if (restored.isNotEmpty())
                {
                    nodes.updateClientVersion(
                        id,
                        clientVersion = restored,
                        clientLatest = node.clientLatest.ifEmpty { restored },
                        clientUpdateAvailable = true,
                        updatedAt = Instant.now().toString(),
                    )
                }
                RollbackNodeClientResult.Ok(node.id.value, rolled.info)
            }
            is ClientRollbackOnHostResult.Failed ->
                RollbackNodeClientResult.Failed(rolled.error, rolled.message)
        }
    }
}
