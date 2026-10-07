package rpcnode.toolkit.nodes.application.test

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.tron.infrastructure.start.TronNodeStart
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.application.disks.GetHostDisksUseCase
import rpcnode.toolkit.nodes.application.disks.GetNodeDiskLayoutUseCase
import rpcnode.toolkit.nodes.application.disks.HostDiskReader
import rpcnode.toolkit.nodes.application.snapshot.ResolveSnapshotDestDirUseCase
import rpcnode.toolkit.nodes.domain.model.HostDiskCatalog
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

class RunNodeLiveTestUseCaseTest
{
    private val nodeId = NodeId.parse("44444444-4444-4444-8444-444444444444")!!
    private val serverId = ServerId.parse("srv-1")!!

    private class Fixture(val nodes: FakeNodeRepository, val useCase: RunNodeLiveTestUseCase)

    private fun fixture(
        host: TestNodeOnHost,
        tip: Long? = null,
        diskLayout: Boolean = true,
    ): Fixture
    {
        val server = Server(
            id = serverId,
            name = "box",
            agentUrl = "http://127.0.0.1:9",
            agentKey = "tok",
            createdAt = "t",
            updatedAt = "t",
        )
        val layout = """
            {
              "strategy": "jbod_2",
              "ledger_dir": "/mnt/raid0/rpcnode/tron/nile/fullnode",
              "roles": { "fullnode": { "dir": "/mnt/raid0/rpcnode/tron/nile/fullnode", "mount": "/mnt/raid0" } }
            }
        """.trimIndent()
        val node = Node(
            id = nodeId,
            serverId = serverId,
            name = "TRON nile",
            network = NetworkId.TRON,
            env = EnvId.NILE,
            status = NodeStatus.SYNC,
            diskLayoutJson = if (diskLayout) layout else "",
            installOptionsJson = """{"snapshot":"full"}""",
            createdAt = "t",
            updatedAt = "t",
        )
        val nodes = FakeNodeRepository(listOf(node))
        val servers = FakeServerRepository(listOf(server))
        val facts = YamlNetworkFactsRepository()
        val resolveDest = ResolveSnapshotDestDirUseCase(
            GetNodeDiskLayoutUseCase(
                nodes = nodes,
                facts = facts,
                hostDisks = GetHostDisksUseCase(
                    servers = servers,
                    reader = HostDiskReader { _, _ ->
                        HostDiskCatalog(disks = emptyList(), mounts = emptyList(), unused = emptyList())
                    },
                ),
            ),
            facts,
        )
        return Fixture(
            nodes,
            RunNodeLiveTestUseCase(
                nodes = nodes,
                servers = servers,
                facts = facts,
                catalog = YamlClientProgramCatalog(),
                resolveDestDir = resolveDest,
                chainStarts = mapOf(NetworkId.TRON to TronNodeStart()),
                testOnHost = host,
                publicTip = { _, _ -> tip },
                clock = { "2026-10-07T00:00:00Z" },
            ),
        )
    }

    private val passingChecks = listOf(
        NodeLiveTestCheck("process", "Node process", true, "pid 1"),
        NodeLiveTestCheck("tcp", "RPC listen", true, "127.0.0.1:18091"),
        NodeLiveTestCheck("rpc", "Chain RPC", true, "height=1000"),
    )

    @Test
    fun passes_and_persists_the_result() = runTest {
        var sent: TestNodeOnHostCommand? = null
        val f = fixture(TestNodeOnHost { _, _, cmd ->
            sent = cmd
            TestNodeOnHostResult(ok = true, checks = passingChecks)
        })

        val report = (f.useCase(nodeId.value) as RunNodeLiveTestResult.Done).report
        assertTrue(report.ok, report.toString())
        assertEquals("/mnt/raid0/rpcnode/tron/nile/fullnode", sent!!.nodeDir)
        assertEquals(18091, sent!!.httpPort)
        assertEquals("config-nile.conf", sent!!.configFile)
        val saved = f.nodes.findById(nodeId)!!
        assertEquals("pass", saved.liveTestStatus)
        assertEquals("2026-10-07T00:00:00Z", saved.liveTestAt)
        assertEquals("", saved.liveTestError)
    }

    @Test
    fun adds_a_tip_check_that_never_fails_the_test() = runTest {
        val f = fixture(TestNodeOnHost { _, _, _ -> TestNodeOnHostResult(ok = true, checks = passingChecks) }, tip = 5000)
        val report = (f.useCase(nodeId.value) as RunNodeLiveTestResult.Done).report
        assertTrue(report.ok)
        val tip = report.checks.last()
        assertEquals("tip", tip.id)
        assertTrue(tip.detail.contains("4000 blocks behind"), tip.detail)
    }

    @Test
    fun a_failing_host_check_fails_and_is_stored_with_its_reason() = runTest {
        val failing = listOf(NodeLiveTestCheck("process", "Node process", false, error = "not running"))
        val f = fixture(TestNodeOnHost { _, _, _ -> TestNodeOnHostResult(ok = false, checks = failing) })

        val report = (f.useCase(nodeId.value) as RunNodeLiveTestResult.Done).report
        assertFalse(report.ok)
        assertEquals("Node process: not running", report.error)
        val saved = f.nodes.findById(nodeId)!!
        assertEquals("fail", saved.liveTestStatus)
        assertEquals("Node process: not running", saved.liveTestError)
    }

    @Test
    fun unknown_node_is_not_found() = runTest {
        val f = fixture(TestNodeOnHost { _, _, _ -> null })
        assertEquals(RunNodeLiveTestResult.NotFound, f.useCase("00000000-0000-4000-8000-000000000000"))
        assertEquals(RunNodeLiveTestResult.NotFound, f.useCase(null))
    }

    @Test
    fun finds_the_node_by_server_network_env() = runTest {
        val f = fixture(TestNodeOnHost { _, _, _ -> TestNodeOnHostResult(ok = true, checks = passingChecks) })
        val result = f.useCase(null, serverId = serverId.value, network = "tron", env = "nile")
        assertTrue(result is RunNodeLiveTestResult.Done)
    }

    @Test
    fun unreachable_agent_and_old_agent_are_reported_not_stored() = runTest {
        val down = fixture(TestNodeOnHost { _, _, _ -> null })
        assertTrue(down.useCase(nodeId.value) is RunNodeLiveTestResult.AgentUnreachable)

        val old = fixture(TestNodeOnHost { _, _, _ ->
            TestNodeOnHostResult(ok = false, error = "http_404", message = "agent has no /api/v1/node/test")
        })
        val r = old.useCase(nodeId.value)
        assertTrue(r is RunNodeLiveTestResult.AgentUnreachable)
        assertTrue((r as RunNodeLiveTestResult.AgentUnreachable).detail.contains("no /api/v1/node/test"))
        assertEquals("", old.nodes.findById(nodeId)!!.liveTestStatus)
    }

    @Test
    fun rejected_token_is_reported() = runTest {
        val f = fixture(TestNodeOnHost { _, _, _ -> TestNodeOnHostResult(ok = false, error = "invalid_agent_key") })
        assertEquals(RunNodeLiveTestResult.InvalidAgentKey, f.useCase(nodeId.value))
    }

    @Test
    fun missing_disk_layout_is_reported() = runTest {
        val f = fixture(TestNodeOnHost { _, _, _ -> null }, diskLayout = false)
        assertEquals(RunNodeLiveTestResult.NoDiskLayout, f.useCase(nodeId.value))
    }
}
