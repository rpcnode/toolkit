package rpcnode.toolkit.agent.infrastructure.node

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rpcnode.toolkit.agent.application.node.RunningNodeRegistry
import rpcnode.toolkit.agent.domain.model.RunningNode

/** In-memory registry with JSON snapshot under agent config dir (survives agent restart). */
class FileRunningNodeRegistry(
    private val storeFile: Path,
) : RunningNodeRegistry
{
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val nodes = ConcurrentHashMap<String, RunningNode>()

    init
    {
        load()
    }

    override fun upsert(node: RunningNode)
    {
        val id = node.nodeId.trim()
        if (id.isEmpty())
        {
            return
        }
        nodes[id] = node.copy(nodeId = id)
        persist()
    }

    override fun remove(nodeId: String)
    {
        nodes.remove(nodeId.trim())
        persist()
    }

    override fun get(nodeId: String): RunningNode? = nodes[nodeId.trim()]

    override fun list(): List<RunningNode> = nodes.values.toList()

    private fun load()
    {
        if (!Files.isRegularFile(storeFile))
        {
            return
        }
        val body = runCatching { Files.readString(storeFile) }.getOrNull() ?: return
        val parsed = runCatching { json.decodeFromString<RunningNodesFile>(body) }.getOrNull() ?: return
        for (item in parsed.nodes)
        {
            val id = item.nodeId.trim()
            if (id.isEmpty()) continue
            nodes[id] = RunningNode(
                nodeId = id,
                network = item.network,
                env = item.env,
                nodeDir = item.nodeDir,
                httpPort = item.httpPort,
                pid = item.pid,
                configFile = item.configFile,
                program = item.program,
                heightKind = item.heightKind,
                logFile = item.logFile,
                clientVersion = item.clientVersion,
            )
        }
    }

    private fun persist()
    {
        try
        {
            Files.createDirectories(storeFile.parent)
            val payload = RunningNodesFile(
                nodes = nodes.values.map {
                    RunningNodeFile(
                        nodeId = it.nodeId,
                        network = it.network,
                        env = it.env,
                        nodeDir = it.nodeDir,
                        httpPort = it.httpPort,
                        pid = it.pid,
                        configFile = it.configFile,
                        program = it.program,
                        heightKind = it.heightKind,
                        logFile = it.logFile,
                        clientVersion = it.clientVersion,
                    )
                },
            )
            Files.writeString(storeFile, json.encodeToString(payload))
        }
        catch (_: Exception)
        {
            // best-effort
        }
    }
}

@Serializable
private data class RunningNodesFile(
    val nodes: List<RunningNodeFile> = emptyList(),
)

@Serializable
private data class RunningNodeFile(
    @SerialName("node_id") val nodeId: String = "",
    val network: String = "",
    val env: String = "",
    @SerialName("node_dir") val nodeDir: String = "",
    @SerialName("http_port") val httpPort: Int = 0,
    val pid: Long = 0,
    @SerialName("config_file") val configFile: String = "",
    val program: String = "",
    @SerialName("height_kind") val heightKind: String = "",
    @SerialName("log_file") val logFile: String = "",
    @SerialName("client_version") val clientVersion: String = "",
)
