package rpcnode.toolkit.agent.application.node

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import rpcnode.toolkit.agent.application.enroll.PanelEnrollmentStore
import rpcnode.toolkit.agent.infrastructure.node.resolveHostClientVersion
import rpcnode.toolkit.agent.infrastructure.proc.NodeDirSizeProbe
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Every [interval], probes height for all running nodes concurrently (chain probes) and pushes
 * a batch to the panel (panel then refreshes public tip into SQLite).
 */
class PushNodeHeightsUseCase(
    private val enrollment: PanelEnrollmentStore,
    private val registry: RunningNodeRegistry,
    private val runtimes: Map<String, ChainNodeRuntime>,
    private val push: PushPanelNodeHeights,
    private val token: String,
    private val dirSize: NodeDirSizeProbe = NodeDirSizeProbe(),
    /** systemd MainPID of a node's unit — the pid changes whenever the unit (re)starts, e.g. after a reboot. */
    private val unitPid: (network: String, env: String) -> Long? = { network, env ->
        HostNodeLaunchSupport.mainPid(HostNodeLaunchSupport.unitName(network, env))
    },
    /** The node still has its systemd unit installed (it may just be stopped, restarting or crash-looping). */
    private val unitInstalled: (network: String, env: String) -> Boolean = { network, env ->
        java.nio.file.Files.isRegularFile(
            java.nio.file.Path.of("/etc/systemd/system", HostNodeLaunchSupport.unitName(network, env)),
        )
    },
)
{
    private val log = LoggerFactory.getLogger(PushNodeHeightsUseCase::class.java)

    suspend operator fun invoke()
    {
        val enrolled = enrollment.read() ?: return
        val panelUrl = enrolled.panelUrl.trim().trimEnd('/')
        if (panelUrl.isEmpty() || enrolled.serverId.isBlank())
        {
            return
        }
        // Nodes without a live process that still own an installed unit: a stop, a restart or a crash loop,
        // not a removed node. They stay registered so heights resume as soon as the unit is up again.
        val parked = mutableSetOf<String>()
        val alive = withContext(Dispatchers.IO) {
            registry.list().mapNotNull { node ->
                if (processAlive(node.pid))
                {
                    return@mapNotNull node
                }
                // The stored pid is gone, but the node is a systemd unit that systemd (re)started under a
                // new pid — after a reboot every node looks "dead" otherwise and is dropped from the registry,
                // which silently ends height reporting for nodes that are in fact running.
                val fresh = unitPid(node.network, node.env)
                if (fresh != null && fresh != node.pid && processAlive(fresh))
                {
                    node.copy(pid = fresh).also { registry.upsert(it) }
                }
                else
                {
                    if (unitInstalled(node.network, node.env))
                    {
                        parked += node.nodeId
                    }
                    null
                }
            }
        }
        for (dead in registry.list().filter { n -> n.nodeId !in parked && alive.none { it.nodeId == n.nodeId } })
        {
            registry.remove(dead.nodeId)
        }
        if (alive.isEmpty())
        {
            return
        }
        val samples = coroutineScope {
            alive.map { node ->
                async(Dispatchers.IO) {
                    val probe = runtimes[node.network.lowercase()]?.height
                    val reading = probe?.reading(
                        nodeDir = node.nodeDir,
                        httpPort = node.httpPort,
                        configFile = node.configFile,
                        env = node.env,
                    )
                    val size = dirSize.sizeBytes(node.nodeDir)
                    if (reading == null && size < 0)
                    {
                        return@async null
                    }
                    // Always re-read host VERSION — panel learns what is on disk, not a stale registry copy.
                    val version = resolveHostClientVersion(node.nodeDir, seed = node.clientVersion)
                    if (version.isNotEmpty() && version != node.clientVersion)
                    {
                        registry.upsert(node.copy(clientVersion = version))
                    }
                    NodeHeightItem(
                        nodeId = node.nodeId,
                        height = reading?.height ?: -1,
                        clientVersion = version,
                        sizeOnDisk = size,
                        syncPct = reading?.syncPct,
                        syncing = reading?.syncing == true,
                    )
                }
            }.awaitAll().filterNotNull()
        }
        if (samples.isEmpty())
        {
            return
        }
        val ok = push(
            panelUrl = panelUrl,
            token = token,
            serverId = enrolled.serverId,
            items = samples,
        )
        if (!ok)
        {
            log.warn("node height push failed ({} samples)", samples.size)
        }
    }
}

class NodeHeightPusher(
    private val push: PushNodeHeightsUseCase,
    private val scope: CoroutineScope,
    private val interval: Duration = 30.seconds,
)
{
    private val log = LoggerFactory.getLogger(NodeHeightPusher::class.java)
    private var started = false

    fun start()
    {
        if (started)
        {
            return
        }
        started = true
        scope.launch {
            while (isActive)
            {
                try
                {
                    push()
                }
                catch (e: Exception)
                {
                    log.warn("height push: {}", e.message)
                }
                delay(interval)
            }
        }
    }
}
