package rpcnode.toolkit.nodes.application.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.FakeClientVersionRepository
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.application.config.EnsureNodeClients
import rpcnode.toolkit.nodes.application.disks.GetHostDisksUseCase
import rpcnode.toolkit.nodes.application.disks.GetNodeDiskLayoutUseCase
import rpcnode.toolkit.nodes.application.disks.HostDiskReader
import rpcnode.toolkit.nodes.application.options.SaveNodeInstallOptionsUseCase
import rpcnode.toolkit.nodes.application.snapshot.ResolveSnapshotDestDirUseCase
import rpcnode.toolkit.nodes.domain.model.HostDiskCatalog
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

class StartNodeUseCaseTest
{
    private val nodeId = NodeId.parse("44444444-4444-4444-8444-444444444444")!!

    private class Fixture(val nodes: FakeNodeRepository, val useCase: StartNodeUseCase)

    private fun fixture(startOnHost: StartNodeOnHost, ensureClients: EnsureNodeClients? = null): Fixture
    {
        val serverId = ServerId.parse("srv-1")!!
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
              "roles": {
                "fullnode": {
                  "dir": "/mnt/raid0/rpcnode/tron/nile/fullnode",
                  "mount": "/mnt/raid0"
                }
              }
            }
        """.trimIndent()
        val node = Node(
            id = nodeId,
            serverId = serverId,
            name = "TRON nile",
            network = NetworkId.TRON,
            env = EnvId.NILE,
            status = NodeStatus.parse("snapshot_complete"),
            diskLayoutJson = layout,
            installOptionsJson = """{"snapshot":"full"}""",
            createdAt = "t",
            updatedAt = "t",
        )
        val nodes = FakeNodeRepository(listOf(node))
        val servers = FakeServerRepository(listOf(server))
        val facts = YamlNetworkFactsRepository()
        val catalog = YamlClientProgramCatalog()
        val save = SaveNodeInstallOptionsUseCase(nodes, facts)
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
        val useCase = StartNodeUseCase(
            saveInstallOptions = save,
            nodes = nodes,
            servers = servers,
            facts = facts,
            catalog = catalog,
            clients = FakeClientVersionRepository(),
            resolveDestDir = resolveDest,
            startOnHost = startOnHost,
            chainStarts = mapOf(NetworkId.TRON to rpcnode.toolkit.chains.tron.infrastructure.start.TronNodeStart()),
            ensureClients = ensureClients,
        )
        return Fixture(nodes, useCase)
    }

    @Test
    fun start_sets_status_sync_after_host_ok_without_resync() = runTest {
        val f = fixture(StartNodeOnHost { _, _, _ -> StartNodeOnHostResult.Ok(pid = 99L) })

        val result = f.useCase(nodeId.value, null)
        assertTrue(result is StartNodeResult.Started, "got $result")
        val started = result as StartNodeResult.Started
        assertEquals(99L, started.pid)
        assertEquals("sync", started.status)
        assertEquals(NodeStatus.SYNC, f.nodes.findById(nodeId)!!.status)
        assertEquals("/mnt/raid0/rpcnode/tron/nile/fullnode", started.path)
    }

    @Test
    fun start_resyncs_clients_once_when_launch_entry_is_missing() = runTest {
        var starts = 0
        var syncs = 0
        val f = fixture(
            startOnHost = StartNodeOnHost { _, _, _ ->
                starts++
                if (starts == 1)
                {
                    StartNodeOnHostResult.Failed("start_failed", "launch entry missing: FullNode.jar")
                }
                else
                {
                    StartNodeOnHostResult.Ok(pid = 7L)
                }
            },
            ensureClients = EnsureNodeClients { _, _ ->
                syncs++
                null
            },
        )

        val result = f.useCase(nodeId.value, null)
        assertTrue(result is StartNodeResult.Started, "got $result")
        assertEquals(2, starts)
        assertEquals(1, syncs)
    }

    @Test
    fun start_reports_sync_failure_instead_of_retrying_blindly() = runTest {
        var starts = 0
        val f = fixture(
            startOnHost = StartNodeOnHost { _, _, _ ->
                starts++
                StartNodeOnHostResult.Failed("start_failed", "launch entry missing: FullNode.jar")
            },
            ensureClients = EnsureNodeClients { _, _ -> "GET http://panel/install failed" },
        )

        val result = f.useCase(nodeId.value, null)
        assertTrue(result is StartNodeResult.SyncFailed, "got $result")
        assertEquals(1, starts)
    }

    @Test
    fun start_does_not_resync_for_other_failures() = runTest {
        var syncs = 0
        val f = fixture(
            startOnHost = StartNodeOnHost { _, _, _ ->
                StartNodeOnHostResult.Failed("start_failed", "port 18091 is busy")
            },
            ensureClients = EnsureNodeClients { _, _ ->
                syncs++
                null
            },
        )

        val result = f.useCase(nodeId.value, null)
        assertTrue(result is StartNodeResult.StartFailed, "got $result")
        assertEquals(0, syncs)
    }
}
