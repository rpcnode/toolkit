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
        val alive = withContext(Dispatchers.IO) {
            registry.list().filter { processAlive(it.pid) }
        }
        for (dead in registry.list().filter { n -> alive.none { it.nodeId == n.nodeId } })
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
