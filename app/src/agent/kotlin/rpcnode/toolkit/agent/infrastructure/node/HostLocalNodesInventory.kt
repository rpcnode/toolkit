package rpcnode.toolkit.agent.infrastructure.node

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.agent.application.node.ListLocalInstalledNodes
import rpcnode.toolkit.agent.application.node.LocalInstalledNode
import rpcnode.toolkit.agent.application.node.RunningNodeRegistry

/**
 * Host inventory for [ListLocalNodesUseCase]:
 * 1. Agent running-nodes registry
 * 2. systemd `rpcnode-<network>-<env>.service`
 * 3. Go tip JSON under `/etc/rpcnode/nodes/`
 * 4. Go leaf `/etc/<network>/<env>/toolkit.env`
 */
class HostLocalNodesInventory(
    private val registry: RunningNodeRegistry,
    private val systemdDir: Path = Path.of("/etc/systemd/system"),
    private val goNodesDir: Path = Path.of("/etc/rpcnode/nodes"),
    private val etcRoot: Path = Path.of("/etc"),
) : ListLocalInstalledNodes
{
    private val json = Json { ignoreUnknownKeys = true }

    override fun invoke(): List<LocalInstalledNode>
    {
        val byKey = linkedMapOf<String, LocalInstalledNode>()
        fun put(item: LocalInstalledNode)
        {
            val network = item.network.trim().lowercase()
            val env = item.env.trim().lowercase()
            if (network.isEmpty() || env.isEmpty())
            {
                return
            }
            val key = "$network/$env"
            val existing = byKey[key]
            if (existing == null)
            {
                byKey[key] = item.copy(network = network, env = env, status = item.status.ifBlank { "present" })
                return
            }
            byKey[key] = existing.copy(
                status = existing.status.ifBlank { item.status }.ifBlank { "present" },
                source = existing.source.ifBlank { item.source },
                publicPort = if (existing.publicPort > 0) existing.publicPort else item.publicPort,
                agentPort = if (existing.agentPort > 0) existing.agentPort else item.agentPort,
                nodeHttpPort = if (existing.nodeHttpPort > 0) existing.nodeHttpPort else item.nodeHttpPort,
                p2pPort = if (existing.p2pPort > 0) existing.p2pPort else item.p2pPort,
            )
        }

        for (n in registry.list())
        {
            put(
                LocalInstalledNode(
                    network = n.network,
                    env = n.env,
                    status = if (n.pid > 0) "running" else "present",
                    source = n.nodeDir.ifBlank { "running-nodes" },
                    nodeHttpPort = n.httpPort,
                ),
            )
        }
        scanSystemd(::put)
        scanGoNodesJson(::put)
        scanGoToolkitEnv(::put)
        return byKey.values.toList()
    }

    private fun scanSystemd(put: (LocalInstalledNode) -> Unit)
    {
        if (!Files.isDirectory(systemdDir))
        {
            return
        }
        try
        {
            Files.list(systemdDir).use { stream ->
                stream.forEach { path ->
                    val name = path.fileName?.toString().orEmpty()
                    val parsed = parseRpcnodeUnitName(name) ?: return@forEach
                    put(
                        LocalInstalledNode(
                            network = parsed.first,
                            env = parsed.second,
                            status = "present",
                            source = path.toAbsolutePath().toString(),
                        ),
                    )
                }
            }
        }
        catch (_: Exception)
        {
            // best-effort inventory
        }
    }

    private fun scanGoNodesJson(put: (LocalInstalledNode) -> Unit)
    {
        if (!Files.isDirectory(goNodesDir))
        {
            return
        }
        try
        {
            Files.list(goNodesDir).use { stream ->
                stream.forEach { path ->
                    val name = path.fileName?.toString().orEmpty()
                    if (!name.endsWith(".json") || !Files.isRegularFile(path))
                    {
                        return@forEach
                    }
                    val raw = try
                    {
                        Files.readString(path)
                    }
                    catch (_: Exception)
                    {
                        return@forEach
                    }
                    val obj = try
                    {
                        json.parseToJsonElement(raw).jsonObject
                    }
                    catch (_: Exception)
                    {
                        return@forEach
                    }
                    var network = obj["network"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    var env = obj["env"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (network.isBlank() || env.isBlank())
                    {
                        val fromName = splitGoNodesFileName(name)
                        network = network.ifBlank { fromName?.first.orEmpty() }
                        env = env.ifBlank { fromName?.second.orEmpty() }
                    }
                    put(
                        LocalInstalledNode(
                            network = network,
                            env = env,
                            status = "present",
                            source = path.toAbsolutePath().toString(),
                            publicPort = obj["public_port"]?.jsonPrimitive?.intOrNull ?: 0,
                            agentPort = obj["agent_port"]?.jsonPrimitive?.intOrNull ?: 0,
                            nodeHttpPort = obj["node_http_port"]?.jsonPrimitive?.intOrNull ?: 0,
                            p2pPort = obj["p2p_port"]?.jsonPrimitive?.intOrNull ?: 0,
                        ),
                    )
                }
            }
        }
        catch (_: Exception)
        {
            // best-effort
        }
    }

    private fun scanGoToolkitEnv(put: (LocalInstalledNode) -> Unit)
    {
        if (!Files.isDirectory(etcRoot))
        {
            return
        }
        try
        {
            Files.list(etcRoot).use { networks ->
                networks.forEach { networkDir ->
                    if (!Files.isDirectory(networkDir))
                    {
                        return@forEach
                    }
                    val network = networkDir.fileName?.toString().orEmpty()
                    if (network.isBlank() || network.startsWith("."))
                    {
                        return@forEach
                    }
                    try
                    {
                        Files.list(networkDir).use { envs ->
                            envs.forEach { envDir ->
                                if (!Files.isDirectory(envDir))
                                {
                                    return@forEach
                                }
                                val env = envDir.fileName?.toString().orEmpty()
                                val envFile = envDir.resolve("toolkit.env")
                                if (env.isBlank() || !Files.isRegularFile(envFile))
                                {
                                    return@forEach
                                }
                                val ports = readToolkitEnvPorts(envFile)
                                put(
                                    LocalInstalledNode(
                                        network = network,
                                        env = env,
                                        status = "present",
                                        source = envFile.toAbsolutePath().toString(),
                                        publicPort = ports.publicPort,
                                        agentPort = ports.agentPort,
                                        nodeHttpPort = ports.nodeHttpPort,
                                        p2pPort = ports.p2pPort,
                                    ),
                                )
                            }
                        }
                    }
                    catch (_: Exception)
                    {
                        // next network
                    }
                }
            }
        }
        catch (_: Exception)
        {
            // best-effort
        }
    }

    private data class ToolkitPorts(
        val publicPort: Int = 0,
        val agentPort: Int = 0,
        val nodeHttpPort: Int = 0,
        val p2pPort: Int = 0,
    )

    private fun readToolkitEnvPorts(path: Path): ToolkitPorts
    {
        var publicPort = 0
        var agentPort = 0
        var nodeHttpPort = 0
        var p2pPort = 0
        try
        {
            for (line in Files.readAllLines(path))
            {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#") || !t.contains('='))
                {
                    continue
                }
                val key = t.substringBefore('=').trim()
                val value = t.substringAfter('=').trim().toIntOrNull() ?: continue
                when (key)
                {
                    "RPCNODE_PUBLIC_PORT", "RPCNODE_GATEWAY_PORT" ->
                        if (publicPort <= 0) publicPort = value
                    "RPCNODE_AGENT_PORT", "RPCNODE_PANEL_PORT" ->
                        if (agentPort <= 0) agentPort = value
                    "RPCNODE_NODE_HTTP_PORT" ->
                        if (nodeHttpPort <= 0) nodeHttpPort = value
                    "RPCNODE_P2P_PORT" ->
                        if (p2pPort <= 0) p2pPort = value
                }
            }
        }
        catch (_: Exception)
        {
            // ignore
        }
        return ToolkitPorts(publicPort, agentPort, nodeHttpPort, p2pPort)
    }

    companion object
    {
        /** `rpcnode-<network>-<env>.service` — env is the last hyphen segment. */
        fun parseRpcnodeUnitName(name: String): Pair<String, String>?
        {
            val n = name.trim()
            if (!n.startsWith("rpcnode-") || !n.endsWith(".service"))
            {
                return null
            }
            val body = n.removePrefix("rpcnode-").removeSuffix(".service")
            val i = body.lastIndexOf('-')
            if (i <= 0 || i >= body.lastIndex)
            {
                return null
            }
            val network = body.substring(0, i).trim()
            val env = body.substring(i + 1).trim()
            if (network.isEmpty() || env.isEmpty())
            {
                return null
            }
            return network.lowercase() to env.lowercase()
        }

        fun splitGoNodesFileName(name: String): Pair<String, String>?
        {
            val body = name.removeSuffix(".json").trim()
            val i = body.lastIndexOf('-')
            if (i <= 0 || i >= body.lastIndex)
            {
                return null
            }
            return body.substring(0, i).lowercase() to body.substring(i + 1).lowercase()
        }
    }
}
