package rpcnode.toolkit.nodes.application.nodeconfig

import java.time.Instant
import rpcnode.toolkit.clients.domain.model.isCatalogPortBindingSource
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.networks.application.snapshot.snapshotTypesFor
import rpcnode.toolkit.networks.domain.model.ClientConfigBindingFacts
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.application.config.ClientConfigLeafPatch
import rpcnode.toolkit.nodes.application.config.clientConfigIniSection
import rpcnode.toolkit.nodes.application.config.clientConfigTemplateName
import rpcnode.toolkit.nodes.application.config.resolveClientConfigAssignments
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.snapshot.ResolveSnapshotDestDirUseCase
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.repository.ServerRepository

data class NodeConfigField(
    val key: String,
    val label: String = "",
    val help: String = "",
    val type: String = "string",
    val group: String = "",
    val protected: Boolean = false,
)

data class NodeConfigDocument(
    val id: String,
    val path: String,
    val format: String,
    val title: String,
    val description: String,
    val content: String,
    val writable: Boolean,
    val restartRequired: Boolean,
    val missing: Boolean = false,
    val fields: List<NodeConfigField> = emptyList(),
    val protectedKeys: List<String> = emptyList(),
)

data class NodeConfigView(
    val network: String,
    val env: String,
    val etcDir: String,
    val documents: List<NodeConfigDocument>,
    val note: String,
)

sealed interface GetNodeConfigResult
{
    data class Ok(val view: NodeConfigView) : GetNodeConfigResult
    data object NotFound : GetNodeConfigResult
    data object ServerNotFound : GetNodeConfigResult
    data object NoDiskLayout : GetNodeConfigResult
    data object InvalidAgentKey : GetNodeConfigResult
    data class AgentUnreachable(val detail: String = "") : GetNodeConfigResult
}

data class SaveNodeConfigDocument(val id: String, val content: String)

sealed interface SaveNodeConfigResult
{
    data class Saved(val written: List<String>, val restarted: Boolean, val message: String) : SaveNodeConfigResult
    data object NotFound : SaveNodeConfigResult
    data object ServerNotFound : SaveNodeConfigResult
    data object NoDiskLayout : SaveNodeConfigResult
    data object ConfirmRequired : SaveNodeConfigResult
    data object NoDocuments : SaveNodeConfigResult
    data object InvalidAgentKey : SaveNodeConfigResult
    data class UnknownDocument(val id: String) : SaveNodeConfigResult
    data class NotWritable(val id: String) : SaveNodeConfigResult
    data class EmptyContent(val id: String) : SaveNodeConfigResult
    data class TooLarge(val id: String) : SaveNodeConfigResult
    data class AgentUnreachable(val detail: String = "") : SaveNodeConfigResult
    data class WriteFailed(val detail: String) : SaveNodeConfigResult
}

/** Binding sources whose values the operator must not change from the config editor. */
private fun ClientConfigBindingFacts.isLocked(): Boolean =
    isCatalogPortBindingSource(source) ||
        source.trim().lowercase() in setOf("disk_role_dir", "disk_role_mount")

/** Panel-side paths to the editable files of one node (all under node_dir). */
internal class NodeConfigFiles(
    val nodeDir: String,
    val template: String?,
    val format: String,
)
{
    val mainPath: String? get() = template?.let { "${nodeDir.trimEnd('/')}/${it.trimStart('/')}" }
    val launchPath: String get() = "${nodeDir.trimEnd('/')}/.toolkit/launch.json"
}

/**
 * Node page "Config".
 * - Clients with a config file (TRON hocon, Bitcoin-family ini): one editable document, `main`.
 *   Ports and data paths bound from the catalog are locked and re-applied on save.
 * - Flag-only clients (no config file): the launch recipe `.toolkit/launch.json`, read-only.
 */
class GetNodeConfigUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val facts: NetworkFactsRepository,
    private val resolveDestDir: ResolveSnapshotDestDirUseCase,
    private val files: HostFiles,
)
{
    suspend operator fun invoke(idRaw: String): GetNodeConfigResult
    {
        val node = findNode(nodes, idRaw) ?: return GetNodeConfigResult.NotFound
        val server = servers.find(node.serverId) ?: return GetNodeConfigResult.ServerNotFound
        val (agentUrl, key) = agentOf(server) ?: return GetNodeConfigResult.AgentUnreachable("missing agent url or key")
        val nodeDir = resolveDestDir(node)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return GetNodeConfigResult.NoDiskLayout
        val config = facts.factsFor(node.network)?.clientConfig
        val layout = nodeConfigFiles(nodeDir, config?.let { clientConfigTemplateName(it, node.env.value) }, config?.format)

        val documents = mutableListOf<NodeConfigDocument>()
        val mainPath = layout.mainPath
        if (mainPath != null && layout.format != "flags")
        {
            val read = files.read(agentUrl, key, mainPath) ?: return GetNodeConfigResult.AgentUnreachable()
            when (read)
            {
                HostFileRead.Unauthorized -> return GetNodeConfigResult.InvalidAgentKey
                is HostFileRead.Failed -> return GetNodeConfigResult.AgentUnreachable(read.detail)
                HostFileRead.TooLarge -> return GetNodeConfigResult.AgentUnreachable("$mainPath is larger than 2 MiB")
                is HostFileRead.Ok ->
                {
                    val locked = config?.bindings.orEmpty().filter { it.isLocked() && !it.path.startsWith("_") }
                    documents += NodeConfigDocument(
                        id = MAIN_ID,
                        path = mainPath,
                        format = layout.format,
                        title = mainPath.substringAfterLast('/'),
                        description = "Client config for ${node.network.value}/${node.env.value}. " +
                            "Ports and data paths are locked; saving restarts the node.",
                        content = read.content,
                        writable = true,
                        restartRequired = true,
                        missing = !read.exists,
                        fields = locked.map {
                            NodeConfigField(
                                key = it.path,
                                label = it.description.orEmpty(),
                                group = "locked",
                                protected = true,
                            )
                        },
                        protectedKeys = locked.map { it.path },
                    )
                }
            }
        }
        else
        {
            val read = files.read(agentUrl, key, layout.launchPath) ?: return GetNodeConfigResult.AgentUnreachable()
            if (read is HostFileRead.Unauthorized)
            {
                return GetNodeConfigResult.InvalidAgentKey
            }
            if (read is HostFileRead.Ok && read.exists)
            {
                documents += NodeConfigDocument(
                    id = LAUNCH_ID,
                    path = layout.launchPath,
                    format = "json",
                    title = "launch.json",
                    description = "How the node is started (flags). Read-only: ports and data paths come from the catalog.",
                    content = read.content,
                    writable = false,
                    restartRequired = false,
                )
            }
        }
        return GetNodeConfigResult.Ok(
            NodeConfigView(
                network = node.network.value,
                env = node.env.value,
                etcDir = nodeDir,
                documents = documents,
                note = "Save writes the file on the host, then restarts the node. Ports and data paths are never editable.",
            ),
        )
    }

    companion object
    {
        const val MAIN_ID = "main"
        const val LAUNCH_ID = "launch"
    }
}

class SaveNodeConfigUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val facts: NetworkFactsRepository,
    private val catalog: ClientProgramCatalog,
    private val resolveDestDir: ResolveSnapshotDestDirUseCase,
    private val files: HostFiles,
    /** Restarts the node after the write; returns an error text, or null on success. */
    private val restartNode: suspend (nodeId: String) -> String?,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    suspend operator fun invoke(
        idRaw: String,
        confirm: Boolean,
        restart: Boolean,
        documents: List<SaveNodeConfigDocument>,
    ): SaveNodeConfigResult
    {
        if (!confirm)
        {
            return SaveNodeConfigResult.ConfirmRequired
        }
        if (documents.isEmpty())
        {
            return SaveNodeConfigResult.NoDocuments
        }
        val node = findNode(nodes, idRaw) ?: return SaveNodeConfigResult.NotFound
        val server = servers.find(node.serverId) ?: return SaveNodeConfigResult.ServerNotFound
        val (agentUrl, key) = agentOf(server) ?: return SaveNodeConfigResult.AgentUnreachable("missing agent url or key")
        val nodeDir = resolveDestDir(node)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return SaveNodeConfigResult.NoDiskLayout
        val config = facts.factsFor(node.network)?.clientConfig
        val layout = nodeConfigFiles(nodeDir, config?.let { clientConfigTemplateName(it, node.env.value) }, config?.format)
        val mainPath = layout.mainPath

        val written = mutableListOf<String>()
        for (doc in documents)
        {
            val id = doc.id.trim()
            if (id != GetNodeConfigUseCase.MAIN_ID)
            {
                return if (id == GetNodeConfigUseCase.LAUNCH_ID)
                {
                    SaveNodeConfigResult.NotWritable(id)
                }
                else
                {
                    SaveNodeConfigResult.UnknownDocument(id)
                }
            }
            if (mainPath == null || layout.format == "flags")
            {
                return SaveNodeConfigResult.UnknownDocument(id)
            }
            if (doc.content.isBlank())
            {
                return SaveNodeConfigResult.EmptyContent(id)
            }
            if (doc.content.toByteArray().size > MAX_BYTES)
            {
                return SaveNodeConfigResult.TooLarge(id)
            }
            val content = lockProtectedKeys(node, config, layout, doc.content)
                ?: return SaveNodeConfigResult.NoDiskLayout
            when (val w = files.write(agentUrl, key, mainPath, content) ?: return SaveNodeConfigResult.AgentUnreachable())
            {
                HostFileWrite.Unauthorized -> return SaveNodeConfigResult.InvalidAgentKey
                is HostFileWrite.Failed -> return SaveNodeConfigResult.WriteFailed(w.detail)
                is HostFileWrite.Ok -> written += w.path
            }
        }

        if (!restart)
        {
            return SaveNodeConfigResult.Saved(written, restarted = false, message = "Config saved. Restart the node to apply it.")
        }
        val restartError = restartNode(node.id.value)
        return if (restartError == null)
        {
            SaveNodeConfigResult.Saved(written, restarted = true, message = "Config saved, node restarted.")
        }
        else
        {
            SaveNodeConfigResult.Saved(
                written,
                restarted = false,
                message = "Config saved, but the restart failed: $restartError",
            )
        }
    }

    /**
     * Ports and data paths come from the catalog, never from the editor: re-apply the resolved
     * values (the same ones the Clients step wrote) over whatever the operator typed.
     * Null when the node has no disk layout to resolve them from.
     */
    private fun lockProtectedKeys(
        node: Node,
        config: rpcnode.toolkit.networks.domain.model.ClientConfigFacts?,
        layout: NodeConfigFiles,
        content: String,
    ): String?
    {
        if (config == null)
        {
            return content
        }
        val diskLayout = decodeNodeDiskLayout(node.diskLayoutJson) ?: return null
        val ports = catalog.programsFor(node.network, node.env).flatMap { it.ports }
        val assignments = resolveClientConfigAssignments(
            config = config,
            layout = diskLayout,
            ports = ports,
            installOptionsJson = node.installOptionsJson,
            snapshotTypes = snapshotTypesFor(facts, node.network, node.env.value),
            env = node.env.value,
        )
        val lockedPaths = config.bindings.filter { it.isLocked() }.map { it.path }.toSet()
        val locked = assignments.filterKeys { it in lockedPaths }
        if (locked.isEmpty())
        {
            return content
        }
        return when (layout.format)
        {
            "ini" -> ClientConfigLeafPatch.applyIni(content, locked, clientConfigIniSection(config, node.env.value))
            else -> ClientConfigLeafPatch.applyHocon(content, locked)
        }
    }

    private companion object
    {
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}

private suspend fun findNode(nodes: NodeRepository, idRaw: String): Node? =
    NodeId.parse(idRaw.trim())?.let { nodes.findById(it) }

private fun agentOf(server: Server): Pair<String, String>?
{
    val url = server.agentUrl.trim()
    val key = server.agentKey.trim()
    return if (url.isEmpty() || key.isEmpty()) null else url to key
}

/** `hoocon` is the catalog's historical spelling of `hocon`. */
private fun nodeConfigFiles(nodeDir: String, template: String?, formatRaw: String?): NodeConfigFiles
{
    val format = when (val f = formatRaw?.trim()?.lowercase().orEmpty())
    {
        "hoocon", "hocon" -> "hocon"
        "" -> "text"
        else -> f
    }
    return NodeConfigFiles(nodeDir, template?.trim()?.takeIf { it.isNotEmpty() }, format)
}
