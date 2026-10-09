package rpcnode.toolkit.agent.application.node

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import rpcnode.toolkit.agent.domain.model.RunningNode
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult

class RemoveNodeStepsUseCaseTest
{
    private class MemoryRegistry : RunningNodeRegistry
    {
        val nodes = mutableMapOf<String, RunningNode>()
        override fun upsert(node: RunningNode) { nodes[node.nodeId] = node }
        override fun remove(nodeId: String) { nodes.remove(nodeId) }
        override fun get(nodeId: String): RunningNode? = nodes[nodeId]
        override fun list(): List<RunningNode> = nodes.values.toList()
    }

    private class FakeOps(
        var active: Boolean = true,
        var failStop: String? = null,
        var failDelete: String? = null,
        var failService: String? = null,
        val existing: MutableSet<Path> = mutableSetOf(),
    ) : RemovalOps
    {
        val calls = mutableListOf<String>()

        override fun unitActive(network: String, env: String) = active
        override fun units(network: String, env: String, nodeDir: Path?) =
            listOf("rpcnode-$network-$env.service", "lighthouse-$network-$env.service")

        override fun stop(network: String, env: String, nodeDir: Path?): HostNodeStartResult
        {
            calls += "stop"
            return failStop?.let { HostNodeStartResult.Failed(it) } ?: HostNodeStartResult.Started(pid = 0)
        }

        override fun removeUnits(units: List<String>, network: String, env: String): HostNodeStartResult
        {
            calls += "units:${units.joinToString(",")}"
            return failService?.let { HostNodeStartResult.Failed(it) } ?: HostNodeStartResult.Started(pid = 0)
        }

        override fun removeSnapshotUnit(nodeId: String)
        {
            calls += "snapshot-unit"
        }

        override fun deleteTree(path: Path, onFile: (deleted: Long) -> Unit): Boolean
        {
            calls += "delete:$path"
            failDelete?.let { throw java.io.IOException(it) }
            return existing.remove(path)
        }
    }

    private fun useCase(ops: RemovalOps, registry: MemoryRegistry = MemoryRegistry(), root: Boolean = true) =
        RemoveNodeStepsUseCase(
            registry = registry,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ops = ops,
            isRoot = { root },
            optRoot = Path.of("/opt"),
        )

    private fun command(wipe: Boolean = true, vararg dirs: String) = RemoveStepsCommand(
        nodeId = "n1",
        network = "tron",
        env = "nile",
        dirs = dirs.toList().ifEmpty { listOf("/data/tron/nile/fullnode") },
        wipeData = wipe,
    )

    private fun RemoveNodeStepsUseCase.await(nodeId: String = "n1"): RemovalState
    {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline)
        {
            val s = progress(nodeId)
            if (s != null && (s.done || s.failed)) return s
            Thread.sleep(20)
        }
        error("removal did not finish: ${progress(nodeId)}")
    }

    @Test
    fun runs_stop_then_files_then_service_in_that_order()
    {
        val ops = FakeOps(existing = mutableSetOf(Path.of("/data/tron/nile/fullnode"), Path.of("/opt/tron/nile")))
        val registry = MemoryRegistry().also {
            it.upsert(RunningNode(nodeId = "n1", network = "tron", env = "nile", nodeDir = "/data/tron/nile/fullnode", httpPort = 1, pid = 1))
        }
        val uc = useCase(ops, registry)
        assertIs<StartRemovalResult.Started>(uc.start(command()))
        val state = uc.await()

        assertTrue(state.done && !state.failed)
        assertEquals(listOf("done", "done", "done"), state.steps.map { it.status })
        assertEquals(
            listOf(
                "stop",
                "delete:${Path.of("/data/tron/nile/fullnode")}",
                "delete:${Path.of("/opt/tron/nile")}",
                "snapshot-unit",
                "units:rpcnode-tron-nile.service,lighthouse-tron-nile.service",
            ),
            ops.calls,
        )
        assertNull(registry.get("n1"), "the node is forgotten by the agent")
    }

    @Test
    fun a_node_that_is_not_running_skips_the_stop_step()
    {
        val uc = useCase(FakeOps(active = false))
        uc.start(command())
        val state = uc.await()
        assertEquals("skipped", state.steps[0].status)
        assertEquals("not running", state.steps[0].detail)
        assertEquals("done", state.steps[2].status)
    }

    @Test
    fun keeping_data_skips_the_files_step_but_still_removes_the_service()
    {
        val ops = FakeOps()
        val uc = useCase(ops)
        uc.start(command(wipe = false))
        val state = uc.await()
        assertEquals("skipped", state.steps[1].status)
        assertTrue(ops.calls.none { it.startsWith("delete:") })
        assertTrue(ops.calls.any { it.startsWith("units:") })
        assertTrue(state.done)
    }

    @Test
    fun a_failed_file_delete_stops_the_job_and_leaves_the_service_alone()
    {
        val ops = FakeOps(failDelete = "Permission denied")
        val uc = useCase(ops)
        uc.start(command())
        val state = uc.await()
        assertTrue(state.failed && !state.done)
        assertEquals("failed", state.steps[1].status)
        assertTrue(state.steps[1].detail.contains("Permission denied"), state.steps[1].detail)
        assertEquals("pending", state.steps[2].status)
        assertTrue(ops.calls.none { it.startsWith("units:") }, "service must stay so the node can be retried")
    }

    @Test
    fun a_failed_stop_is_reported_on_the_first_step()
    {
        val uc = useCase(FakeOps(failStop = "unit is stuck"))
        uc.start(command())
        val state = uc.await()
        assertTrue(state.failed)
        assertEquals("failed", state.steps[0].status)
        assertEquals("unit is stuck", state.error)
    }

    @Test
    fun a_second_start_while_running_returns_the_running_job_and_a_retry_after_failure_runs_again()
    {
        val ops = FakeOps(failDelete = "busy")
        val uc = useCase(ops)
        uc.start(command())
        uc.await()
        ops.failDelete = null
        assertIs<StartRemovalResult.Started>(uc.start(command()))
        assertTrue(uc.await().done)
    }

    @Test
    fun unsafe_folders_are_refused_before_anything_runs()
    {
        val ops = FakeOps()
        val uc = useCase(ops)
        for (bad in listOf("/", "/etc", "/usr/lib/x", "/opt/rpcnode", "/opt/rpcnode/lib", "/data/../etc/x", "relative/path", "/boot/a/b"))
        {
            assertIs<StartRemovalResult.Invalid>(uc.start(command(true, bad)), bad)
        }
        assertTrue(ops.calls.isEmpty())
        assertNull(uc.progress("n1"))
    }

    @Test
    fun without_root_nothing_is_touched()
    {
        val ops = FakeOps()
        assertEquals(StartRemovalResult.NotRoot, useCase(ops, root = false).start(command()))
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun safe_dir_accepts_typical_node_folders()
    {
        for (ok in listOf("/data/tron/nile/fullnode", "/mnt/disk1/eth/sepolia/execution", "/opt/tron/nile/fullnode", "/var/lib/rpcnodes/a/b"))
        {
            assertNotNull(RemoveNodeStepsUseCase.safeNodeDir(ok), ok)
        }
    }
}
