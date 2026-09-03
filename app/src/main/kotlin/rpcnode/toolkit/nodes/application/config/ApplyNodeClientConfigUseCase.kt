package rpcnode.toolkit.nodes.application.config

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.clients.application.InstallPlan
import rpcnode.toolkit.clients.application.InstallPlanFile
import rpcnode.toolkit.clients.application.InstallPlanWriter
import rpcnode.toolkit.clients.application.downloadone.DownloadClientProgramResult
import rpcnode.toolkit.clients.application.downloadone.DownloadClientProgramUseCase
import rpcnode.toolkit.clients.application.inferArchFromFileName
import rpcnode.toolkit.clients.application.inferLaunch
import rpcnode.toolkit.clients.application.installPlanFilesIncludingVersion
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.networks.domain.model.NetworkPinOnly
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.networks.application.snapshot.snapshotTypesFor
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.options.SaveNodeInstallOptionsResult
import rpcnode.toolkit.nodes.application.options.SaveNodeInstallOptionsUseCase
import rpcnode.toolkit.nodes.application.snapshot.ResolveSnapshotDestDirUseCase
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

sealed interface ApplyNodeClientConfigResult
{
    data class Applied(
        val nodeId: String,
        val path: String,
        val installOptionsJson: String,
        val files: List<String> = emptyList(),
    ) : ApplyNodeClientConfigResult

    data object NotFound : ApplyNodeClientConfigResult
    data object ServerNotFound : ApplyNodeClientConfigResult
    data object NoClientConfig : ApplyNodeClientConfigResult
    data object NoDiskLayout : ApplyNodeClientConfigResult
    data object TemplateMissing : ApplyNodeClientConfigResult
    data object InvalidType : ApplyNodeClientConfigResult
    data class AgentUnreachable(val detail: String = "") : ApplyNodeClientConfigResult
    data class SyncFailed(val error: String, val message: String) : ApplyNodeClientConfigResult
}

/**
 * Node-install Clients step: download catalog programs onto the panel (CDN/GitHub),
 * write install-plan.yml, then ask the host agent to pull files into node_dir.
 * Does **not** start the node process.
 */
class ApplyNodeClientConfigUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val facts: NetworkFactsRepository,
    private val catalog: ClientProgramCatalog,
    private val saveInstallOptions: SaveNodeInstallOptionsUseCase,
    private val resolveDestDir: ResolveSnapshotDestDirUseCase,
    private val syncOnHost: SyncClientOnHost,
    private val installPlanWriter: InstallPlanWriter,
    private val clientsDestDir: Path,
    private val downloadClient: DownloadClientProgramUseCase,
)
{
    suspend operator fun invoke(
        idRaw: String,
        installOptionsJson: String?,
        snapshotType: String? = null,
    ): ApplyNodeClientConfigResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return ApplyNodeClientConfigResult.NotFound
        val node = nodes.findById(id) ?: return ApplyNodeClientConfigResult.NotFound
        val server = servers.find(node.serverId) ?: return ApplyNodeClientConfigResult.ServerNotFound

        when (val saved = saveInstallOptions(idRaw, snapshotType = snapshotType, installOptionsJson))
        {
            is SaveNodeInstallOptionsResult.Saved -> Unit
            SaveNodeInstallOptionsResult.NotFound -> return ApplyNodeClientConfigResult.NotFound
            SaveNodeInstallOptionsResult.InvalidType -> return ApplyNodeClientConfigResult.InvalidType
        }
        val fresh = nodes.findById(id) ?: return ApplyNodeClientConfigResult.NotFound

        val networkFacts = facts.factsFor(node.network) ?: return ApplyNodeClientConfigResult.NoClientConfig
        val clientConfig = networkFacts.clientConfig ?: return ApplyNodeClientConfigResult.NoClientConfig
        val flagsOnly = clientConfig.format.trim().lowercase() == "flags"
        if (!flagsOnly && clientConfig.bindings.isEmpty())
        {
            return ApplyNodeClientConfigResult.NoClientConfig
        }

        val layout = decodeNodeDiskLayout(fresh.diskLayoutJson)
            ?: return ApplyNodeClientConfigResult.NoDiskLayout
        val nodeDir = resolveDestDir(fresh)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ApplyNodeClientConfigResult.NoDiskLayout

        val templateName = if (flagsOnly)
        {
            null
        }
        else
        {
            clientConfigTemplateName(clientConfig, node.env.value)
                ?: return ApplyNodeClientConfigResult.TemplateMissing
        }

        val programs = catalog.programsFor(node.network, node.env)
        val pinOnly = NetworkPinOnly.isPinOnly(node.network)
        if (programs.isEmpty() && !pinOnly)
        {
            return ApplyNodeClientConfigResult.TemplateMissing
        }
        for (spec in programs)
        {
            when (val downloaded = downloadClient(spec, force = false))
            {
                is DownloadClientProgramResult.Ok -> Unit
                is DownloadClientProgramResult.Failed ->
                    return ApplyNodeClientConfigResult.SyncFailed(
                        error = "client_download",
                        message = downloaded.error,
                    )
            }
        }

        val clientsEnvDir = clientsDestDir
            .resolve(node.network.value)
            .resolve(node.env.value)
        if (!Files.isDirectory(clientsEnvDir))
        {
            if (!pinOnly)
            {
                return ApplyNodeClientConfigResult.TemplateMissing
            }
            try
            {
                Files.createDirectories(clientsEnvDir)
            }
            catch (e: Exception)
            {
                return ApplyNodeClientConfigResult.SyncFailed(
                    error = "pin_clients_dir",
                    message = e.message ?: "mkdir clients dir failed",
                )
            }
        }
        ensureInstallPlan(
            dir = clientsEnvDir,
            network = node.network.value,
            env = node.env.value,
            program = clientConfig.program.ifBlank {
                programs.firstOrNull()?.programId.orEmpty()
            },
        )

        val ports = programs.flatMap { it.ports }
        val snapshotTypes = snapshotTypesFor(facts, node.network, node.env.value)
        val assignments = resolveClientConfigAssignments(
            config = clientConfig,
            layout = layout,
            ports = ports,
            installOptionsJson = fresh.installOptionsJson,
            snapshotTypes = snapshotTypes,
        )
        // format: flags — still create datadir paths on the host; do not patch a conf file.
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

        if (server.agentUrl.isBlank() || server.agentKey.isBlank())
        {
            return ApplyNodeClientConfigResult.AgentUnreachable("missing agent url or key")
        }
        val synced = syncOnHost.sync(
            server.agentUrl,
            server.agentKey,
            ClientSyncOnHostCommand(
                network = node.network.value,
                env = node.env.value,
                nodeDir = nodeDir,
                configAssignments = patchAssignments,
                configFormat = clientConfig.format.ifBlank { "hoocon" },
                configFile = templateName,
                configIniSection = clientConfigIniSection(clientConfig, node.env.value),
                configOmitIniKeys = omitIniKeys,
            ),
        ) ?: return ApplyNodeClientConfigResult.AgentUnreachable()

        return when (synced)
        {
            is ClientSyncOnHostResult.Ok ->
                ApplyNodeClientConfigResult.Applied(
                    nodeId = fresh.id.value,
                    path = synced.configPath ?: nodeDir,
                    installOptionsJson = fresh.installOptionsJson,
                    files = synced.files,
                )
            is ClientSyncOnHostResult.Failed ->
                ApplyNodeClientConfigResult.SyncFailed(error = synced.error, message = synced.message)
        }
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
