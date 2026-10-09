package rpcnode.toolkit.nodes.application.process

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.servers.domain.model.ServerId

class StopAllNodesUseCaseTest
{
    private fun node(n: Int, status: String) = Node(
        id = NodeId.parse("00000000-0000-4000-8000-00000000000$n")!!,
        serverId = ServerId.parse("srv-1")!!,
        name = "node $n",
        network = NetworkId.TRON,
        env = EnvId.MAINNET,
        status = NodeStatus.parse(status),
        createdAt = "t",
        updatedAt = "t",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun StopAllNodesUseCase.awaitFinished(): StopAllState
    {
        repeat(500) {
            val s = progress()
            if (!s.running && s.finishedAt.isNotEmpty()) return s
            delay(10)
        }
        error("did not finish: ${progress()}")
    }

    @Test
    fun only_nodes_with_a_running_service_are_stopped_and_all_of_them_at_once()
    {
        runBlocking {
            val repo = FakeNodeRepository(
                listOf(node(1, "sync"), node(2, "active"), node(3, "active"), node(4, "stopped"), node(5, "awaiting_ports")),
            )
            // every stop waits until all three are in flight: only true parallelism gets past this
            val inFlight = AtomicInteger()
            val allArrived = CompletableDeferred<Unit>()
            val stopped = mutableListOf<String>()
            val uc = StopAllNodesUseCase(
                nodes = repo,
                stop = { id ->
                    if (inFlight.incrementAndGet() == 3) allArrived.complete(Unit)
                    allArrived.await()
                    synchronized(stopped) { stopped += id }
                    ControlNodeProcessResult.Ok(id, 0, "stop")
                },
                scope = scope,
            )
            val started = uc.start()
            assertEquals(3, started.items.size)
            val end = uc.awaitFinished()

            assertEquals(3, stopped.size)
            assertEquals(listOf("done", "done", "done"), end.items.map { it.status })
            assertEquals(0, end.failed)
        }
    }

    @Test
    fun a_failing_node_does_not_stop_the_others_and_is_reported()
    {
        runBlocking {
            val repo = FakeNodeRepository(listOf(node(1, "sync"), node(2, "active")))
            val bad = node(1, "sync").id.value
            val uc = StopAllNodesUseCase(
                nodes = repo,
                stop = { id ->
                    if (id == bad) ControlNodeProcessResult.AgentUnreachable else ControlNodeProcessResult.Ok(id, 0, "stop")
                },
                scope = scope,
            )
            uc.start()
            val end = uc.awaitFinished()
            assertEquals(1, end.failed)
            assertEquals(1, end.done)
            assertEquals("host agent did not answer", end.items.first { it.nodeId == bad }.detail)
        }
    }

    @Test
    fun nothing_running_finishes_immediately()
    {
        runBlocking {
            val uc = StopAllNodesUseCase(FakeNodeRepository(listOf(node(1, "stopped"))), { error("not called") }, scope)
            val s = uc.start()
            assertFalse(s.running)
            assertTrue(s.items.isEmpty())
        }
    }

    @Test
    fun a_second_click_while_running_joins_the_same_run()
    {
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val uc = StopAllNodesUseCase(
                nodes = FakeNodeRepository(listOf(node(1, "sync"))),
                stop = { id ->
                    calls.incrementAndGet()
                    gate.await()
                    ControlNodeProcessResult.Ok(id, 0, "stop")
                },
                scope = scope,
            )
            val first = uc.start()
            val second = uc.start()
            assertTrue(first.running && second.running)
            assertEquals(first.startedAt, second.startedAt)
            gate.complete(Unit)
            uc.awaitFinished()
            assertEquals(1, calls.get())
        }
    }
}
