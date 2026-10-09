package rpcnode.toolkit.nodes.application.process

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.nodes.domain.repository.NodeRepository

data class StopAllItem(
    val nodeId: String,
    val name: String,
    val network: String,
    val env: String,
    /** pending | running | done | failed */
    val status: String,
    val detail: String = "",
)

data class StopAllState(
    val running: Boolean,
    val startedAt: String = "",
    val finishedAt: String = "",
    val items: List<StopAllItem> = emptyList(),
)
{
    val failed: Int get() = items.count { it.status == "failed" }
    val done: Int get() = items.count { it.status == "done" }
}

/**
 * "Stop all": gracefully stops every node whose service is up, all hosts and nodes in parallel,
 * so the machine can be shut down without corrupting node databases. Each stop waits for the
 * service to exit on its own (the unit's `TimeoutStopSec` decides when systemd gives up).
 *
 * Nodes that are only downloading a snapshot are left alone: stopping that job wipes the download.
 */
class StopAllNodesUseCase(
    private val nodes: NodeRepository,
    private val stop: suspend (nodeId: String) -> ControlNodeProcessResult,
    private val scope: CoroutineScope,
    private val parallelism: Int = 16,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    private val log = LoggerFactory.getLogger(StopAllNodesUseCase::class.java)
    private val state = AtomicReference(StopAllState(running = false))

    fun progress(): StopAllState = state.get()

    suspend fun start(): StopAllState
    {
        val current = state.get()
        if (current.running)
        {
            return current
        }
        val targets = nodes.list().filter { NodeStatus.unitRunning(it.status) }
        val initial = StopAllState(
            running = targets.isNotEmpty(),
            startedAt = clock(),
            finishedAt = if (targets.isEmpty()) clock() else "",
            items = targets.map { it.toItem("pending") },
        )
        // a second click while we were listing nodes must not start a second run
        if (!state.compareAndSet(current, initial))
        {
            return state.get()
        }
        if (targets.isNotEmpty())
        {
            scope.launch(Dispatchers.IO) { runAll(targets) }
        }
        return initial
    }

    private suspend fun runAll(targets: List<Node>)
    {
        val gate = Semaphore(parallelism.coerceAtLeast(1))
        try
        {
            targets.map { node ->
                scope.async(Dispatchers.IO) {
                    gate.withPermit { stopOne(node) }
                }
            }.awaitAll()
        }
        finally
        {
            state.updateAndGet { it.copy(running = false, finishedAt = clock()) }
        }
    }

    private suspend fun stopOne(node: Node)
    {
        val id = node.id.value
        update(id, "running", "stopping…")
        val result = try
        {
            stop(id)
        }
        catch (e: Exception)
        {
            log.warn("stop-all {} failed: {}", id, e.message)
            ControlNodeProcessResult.Failed("exception", e.message ?: e.javaClass.simpleName)
        }
        when (result)
        {
            is ControlNodeProcessResult.Ok -> update(id, "done", "stopped")
            ControlNodeProcessResult.NotFound -> update(id, "failed", "node not found")
            ControlNodeProcessResult.ServerNotFound -> update(id, "failed", "server not found")
            ControlNodeProcessResult.AgentUnreachable -> update(id, "failed", "host agent did not answer")
            ControlNodeProcessResult.InvalidAgentKey -> update(id, "failed", "invalid agent key")
            is ControlNodeProcessResult.Failed -> update(id, "failed", result.message.ifBlank { result.error })
        }
    }

    private fun update(nodeId: String, status: String, detail: String)
    {
        state.updateAndGet { s ->
            s.copy(items = s.items.map { if (it.nodeId == nodeId) it.copy(status = status, detail = detail) else it })
        }
    }

    private fun Node.toItem(status: String) = StopAllItem(
        nodeId = id.value,
        name = name,
        network = network.value,
        env = env.value,
        status = status,
    )
}
