package rpcnode.toolkit.agent.application.node

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.agent.application.enroll.PanelEnrollmentStore
import rpcnode.toolkit.agent.domain.model.PanelEnrollment
import rpcnode.toolkit.agent.domain.model.RunningNode
import rpcnode.toolkit.agent.infrastructure.proc.NodeDirSizeProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult

class PushNodeHeightsUseCaseTest
{
    private val alivePid = ProcessHandle.current().pid()
    private val deadPid = 2_000_000_000L

    private class MemoryRegistry(nodes: List<RunningNode>) : RunningNodeRegistry
    {
        val nodes = nodes.associateBy { it.nodeId }.toMutableMap()
        override fun upsert(node: RunningNode) { nodes[node.nodeId] = node }
        override fun remove(nodeId: String) { nodes.remove(nodeId) }
        override fun get(nodeId: String): RunningNode? = nodes[nodeId]
        override fun list(): List<RunningNode> = nodes.values.toList()
    }

    private val enrollment = object : PanelEnrollmentStore
    {
        override suspend fun read() = PanelEnrollment(panelUrl = "http://panel:8094", serverId = "srv-1")
        override suspend fun write(enrollment: PanelEnrollment) = Unit
        override suspend fun clear() = Unit
    }

    private val runtime = ChainNodeRuntime(
        network = "tron",
        starter = { _, _, _, _, _ -> HostNodeStartResult.Failed("unused") },
        height = object : HostNodeHeightProbe
        {
            override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long? = 4242L
        },
    )

    private fun node(pid: Long) = RunningNode(
        nodeId = "n1", network = "tron", env = "nile", nodeDir = "/tmp/none", httpPort = 18091, pid = pid,
    )

    private suspend fun runPush(
        registry: MemoryRegistry,
        unitInstalled: Boolean = false,
        unitPid: (String, String) -> Long?,
    ): List<NodeHeightItem>
    {
        val sent = mutableListOf<NodeHeightItem>()
        PushNodeHeightsUseCase(
            enrollment = enrollment,
            registry = registry,
            runtimes = mapOf("tron" to runtime),
            push = { _, _, _, items -> sent += items; true },
            token = "t",
            dirSize = NodeDirSizeProbe(),
            unitPid = unitPid,
            unitInstalled = { _, _ -> unitInstalled },
        )()
        return sent
    }

    @Test
    fun after_a_reboot_the_node_is_followed_to_its_new_systemd_pid_instead_of_being_dropped() = runTest {
        // the registry still holds the pid from before the reboot; systemd started the unit again
        val registry = MemoryRegistry(listOf(node(pid = deadPid)))
        val sent = runPush(registry) { _, _ -> alivePid }

        assertEquals(alivePid, registry.get("n1")?.pid, "registry follows the unit's new pid")
        assertEquals(listOf("n1"), sent.map { it.nodeId }, "heights keep flowing")
        assertEquals(4242L, sent.single().height)
    }

    @Test
    fun a_node_that_is_really_gone_is_still_removed() = runTest {
        val registry = MemoryRegistry(listOf(node(pid = deadPid)))
        val sent = runPush(registry) { _, _ -> null }
        assertNull(registry.get("n1"))
        assertEquals(emptyList(), sent)
    }

    @Test
    fun a_stopped_node_whose_unit_is_still_installed_stays_registered()
    {
        runTest {
            // crash loop, manual systemctl restart or a stop: no live pid right now, but the node is not gone
            val registry = MemoryRegistry(listOf(node(pid = deadPid)))
            val sent = runPush(registry, unitInstalled = true) { _, _ -> null }
            assertEquals("n1", registry.get("n1")?.nodeId)
            assertEquals(emptyList(), sent)
        }
    }

    @Test
    fun a_unit_pointing_at_a_dead_process_does_not_resurrect_the_entry() = runTest {
        val registry = MemoryRegistry(listOf(node(pid = deadPid)))
        runPush(registry) { _, _ -> deadPid + 1 }
        assertNull(registry.get("n1"))
    }

    @Test
    fun a_live_pid_is_used_without_asking_systemd() = runTest {
        val registry = MemoryRegistry(listOf(node(pid = alivePid)))
        var asked = 0
        runPush(registry) { _, _ -> asked++; null }
        assertEquals(0, asked)
        assertEquals(alivePid, registry.get("n1")?.pid)
    }
}
