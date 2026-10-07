package rpcnode.toolkit.agent.application.node

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

data class NodeTestCommand(
    val nodeId: String,
    val network: String,
    val env: String,
    val nodeDir: String,
    val httpPort: Int,
    val configFile: String = "",
)

data class NodeTestCheck(
    val id: String,
    val title: String,
    val ok: Boolean,
    val detail: String = "",
    val error: String = "",
)

data class NodeTestReport(
    val ok: Boolean,
    val network: String,
    val env: String,
    val checks: List<NodeTestCheck>,
    val error: String = "",
)

/**
 * Read-only live check of a node on this host — no start / stop / wipe:
 * 1. node process is running (systemd MainPID, registry pid as fallback),
 * 2. its HTTP/RPC port accepts connections,
 * 3. the chain RPC answers with a block height (same probe the height push uses).
 * Stops at the first failing step: later ones cannot pass without it.
 */
class TestNodeUseCase(
    private val registry: RunningNodeRegistry,
    private val runtimes: Map<String, ChainNodeRuntime>,
    private val mainPid: (unit: String) -> Long? = HostNodeLaunchSupport::mainPid,
    private val alive: (pid: Long) -> Boolean = ::processAlive,
    private val dial: (host: String, port: Int, timeoutMs: Int) -> Unit = ::tcpDial,
    private val rpcTimeoutMs: Long = 10_000,
)
{
    suspend operator fun invoke(command: NodeTestCommand): NodeTestReport
    {
        val network = command.network.trim().lowercase()
        val env = command.env.trim().lowercase()
        val checks = mutableListOf<NodeTestCheck>()
        fun done(error: String = "") = NodeTestReport(
            ok = checks.isNotEmpty() && checks.all { it.ok },
            network = network,
            env = env,
            checks = checks.toList(),
            error = error.ifBlank { checks.firstOrNull { !it.ok }?.let { it.error.ifBlank { it.detail } }.orEmpty() },
        )

        val runtime = runtimes[network]
        if (runtime == null)
        {
            checks += NodeTestCheck("runtime", "Chain runtime", ok = false, error = "network $network is not supported by this agent")
            return done()
        }

        val unit = HostNodeLaunchSupport.unitName(network, env)
        val pid = withContext(Dispatchers.IO) {
            mainPid(unit) ?: registry.get(command.nodeId.trim())?.pid?.takeIf { it > 0 }
        }
        if (pid == null || !withContext(Dispatchers.IO) { alive(pid) })
        {
            checks += NodeTestCheck(
                "process",
                "Node process",
                ok = false,
                error = "not running (systemd unit $unit is inactive) — start the node first",
            )
            return done()
        }
        checks += NodeTestCheck("process", "Node process", ok = true, detail = "pid $pid ($unit)")

        val port = command.httpPort
        if (port <= 0)
        {
            checks += NodeTestCheck("tcp", "RPC listen", ok = false, error = "HTTP port is unknown for this node")
            return done()
        }
        val tcp = withContext(Dispatchers.IO) { runCatching { dial("127.0.0.1", port, TCP_TIMEOUT_MS) } }
        if (tcp.isFailure)
        {
            checks += NodeTestCheck(
                "tcp",
                "RPC listen",
                ok = false,
                error = "127.0.0.1:$port — ${tcp.exceptionOrNull()?.message?.ifBlank { null } ?: "connection failed"}",
            )
            return done()
        }
        checks += NodeTestCheck("tcp", "RPC listen", ok = true, detail = "127.0.0.1:$port")

        val reading = try
        {
            withTimeout(rpcTimeoutMs) {
                runtime.height.reading(
                    nodeDir = command.nodeDir,
                    httpPort = port,
                    configFile = command.configFile,
                    env = env,
                )
            }
        }
        catch (e: TimeoutCancellationException)
        {
            null
        }
        catch (e: CancellationException)
        {
            throw e
        }
        catch (_: Exception)
        {
            null
        }
        if (reading == null || reading.height < 0)
        {
            checks += NodeTestCheck(
                "rpc",
                "Chain RPC",
                ok = false,
                error = "port is open but the RPC did not return a block height (node still starting?)",
            )
            return done()
        }
        val state = when
        {
            reading.syncing && reading.syncPct != null -> ", syncing %.1f%%".format(reading.syncPct)
            reading.syncing -> ", syncing"
            else -> ""
        }
        checks += NodeTestCheck("rpc", "Chain RPC", ok = true, detail = "height=${reading.height}$state")
        return done()
    }

    private companion object
    {
        const val TCP_TIMEOUT_MS = 3_000
    }
}

private fun tcpDial(host: String, port: Int, timeoutMs: Int)
{
    Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
}
