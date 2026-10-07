package rpcnode.toolkit.agent.application.node

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.agent.domain.model.RunningNode
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult

class TestNodeUseCaseTest
{
    private val registry = object : RunningNodeRegistry
    {
        override fun upsert(node: RunningNode) = Unit
        override fun remove(nodeId: String) = Unit
        override fun get(nodeId: String): RunningNode? = null
        override fun list(): List<RunningNode> = emptyList()
    }

    private fun runtime(reading: HostNodeHeightReading?) = ChainNodeRuntime(
        network = "tron",
        starter = { _, _, _, _, _ -> HostNodeStartResult.Failed("unused") },
        height = object : HostNodeHeightProbe
        {
            override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long? =
                reading?.height

            override suspend fun reading(
                nodeDir: String,
                httpPort: Int,
                configFile: String,
                env: String,
            ): HostNodeHeightReading? = reading
        },
    )

    private fun useCase(
        reading: HostNodeHeightReading?,
        pid: Long? = 4321L,
        alive: Boolean = true,
        dialFails: Boolean = false,
    ) = TestNodeUseCase(
        registry = registry,
        runtimes = mapOf("tron" to runtime(reading)),
        mainPid = { pid },
        alive = { alive },
        dial = { _, _, _ -> if (dialFails) throw IOException("Connection refused") },
    )

    private val command = NodeTestCommand(
        nodeId = "n1",
        network = "tron",
        env = "nile",
        nodeDir = "/mnt/r/rpcnode/tron/nile/fullnode",
        httpPort = 18091,
    )

    @Test
    fun passes_when_process_port_and_rpc_are_ok() = runTest {
        val report = useCase(HostNodeHeightReading(height = 12_345)).invoke(command)
        assertTrue(report.ok, report.toString())
        assertEquals(listOf("process", "tcp", "rpc"), report.checks.map { it.id })
        assertEquals("height=12345", report.checks.last().detail)
        assertEquals("", report.error)
    }

    @Test
    fun reports_sync_progress_but_still_passes() = runTest {
        val report = useCase(HostNodeHeightReading(height = 10, syncPct = 42.0, syncing = true)).invoke(command)
        assertTrue(report.ok)
        assertTrue(report.checks.last().detail.startsWith("height=10, syncing"))
    }

    @Test
    fun stops_at_the_process_check_when_the_node_is_not_running() = runTest {
        val report = useCase(HostNodeHeightReading(height = 1), pid = null).invoke(command)
        assertFalse(report.ok)
        assertEquals(listOf("process"), report.checks.map { it.id })
        assertTrue(report.error.contains("not running"), report.error)
    }

    @Test
    fun fails_when_the_pid_is_dead() = runTest {
        val report = useCase(HostNodeHeightReading(height = 1), alive = false).invoke(command)
        assertFalse(report.ok)
        assertEquals(listOf("process"), report.checks.map { it.id })
    }

    @Test
    fun fails_when_the_port_is_closed() = runTest {
        val report = useCase(HostNodeHeightReading(height = 1), dialFails = true).invoke(command)
        assertFalse(report.ok)
        assertEquals(listOf("process", "tcp"), report.checks.map { it.id })
        assertTrue(report.error.contains("18091"), report.error)
    }

    @Test
    fun fails_when_the_rpc_has_no_height() = runTest {
        val report = useCase(reading = null).invoke(command)
        assertFalse(report.ok)
        assertEquals(listOf("process", "tcp", "rpc"), report.checks.map { it.id })
        assertFalse(report.checks.last().ok)
    }

    @Test
    fun fails_without_a_port() = runTest {
        val report = useCase(HostNodeHeightReading(height = 1)).invoke(command.copy(httpPort = 0))
        assertFalse(report.ok)
        assertEquals("tcp", report.checks.last().id)
    }

    @Test
    fun unsupported_network_is_reported() = runTest {
        val report = useCase(HostNodeHeightReading(height = 1)).invoke(command.copy(network = "nope"))
        assertFalse(report.ok)
        assertEquals("runtime", report.checks.single().id)
    }
}
