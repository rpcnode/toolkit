package rpcnode.toolkit.nodes.application.nodeconfig

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.InMemoryHostFiles
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

class NodeConfigUseCasesTest
{
    private val nodeId = NodeId.parse("44444444-4444-4444-8444-444444444444")!!
    private val serverId = ServerId.parse("srv-1")!!
    private val dir = "/mnt/raid0/rpcnode/tron/nile/fullnode"
    private val confPath = "$dir/config-nile.conf"

    private class Fixture(
        val files: InMemoryHostFiles,
        val get: GetNodeConfigUseCase,
        val save: SaveNodeConfigUseCase,
        val restarts: MutableList<String>,
    )

    private fun fixture(restartError: String? = null): Fixture
    {
        val server = Server(id = serverId, name = "box", agentUrl = "http://127.0.0.1:9", agentKey = "tok", createdAt = "t", updatedAt = "t")
        val layout = """
            {
              "strategy": "jbod_2",
              "ledger_dir": "$dir",
              "roles": [ { "id": "fullnode", "dir": "$dir", "mount": "/mnt/raid0" } ]
            }
        """.trimIndent()
        val node = Node(
            id = nodeId,
            serverId = serverId,
            name = "TRON nile",
            network = NetworkId.TRON,
            env = EnvId.NILE,
            status = NodeStatus.SYNC,
            diskLayoutJson = layout,
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
        val files = InMemoryHostFiles(mutableMapOf(confPath to "node.listen.port = 18889\nnode.maxConnections = 30\n"))
        val restarts = mutableListOf<String>()
        return Fixture(
            files,
            GetNodeConfigUseCase(nodes, servers, facts, resolveDest, files),
            SaveNodeConfigUseCase(
                nodes = nodes,
                servers = servers,
                facts = facts,
                catalog = YamlClientProgramCatalog(),
                resolveDestDir = resolveDest,
                files = files,
                restartNode = { id ->
                    restarts += id
                    restartError
                },
            ),
            restarts,
        )
    }

    @Test
    fun get_returns_the_client_config_with_locked_ports() = runTest {
        val f = fixture()
        val view = (f.get(nodeId.value) as GetNodeConfigResult.Ok).view
        val doc = view.documents.single()
        assertEquals("main", doc.id)
        assertEquals(confPath, doc.path)
        assertEquals("hocon", doc.format)
        assertTrue(doc.writable)
        assertTrue(doc.restartRequired)
        assertFalse(doc.missing)
        assertTrue(doc.content.contains("node.maxConnections = 30"))
        assertTrue("node.listen.port" in doc.protectedKeys)
        assertTrue(doc.fields.all { it.protected })
        assertFalse("storage.db.directory" in doc.protectedKeys, "relative literal dirs are editable")
    }

    @Test
    fun get_marks_a_not_yet_written_file_as_missing() = runTest {
        val f = fixture()
        f.files.files.clear()
        val doc = (f.get(nodeId.value) as GetNodeConfigResult.Ok).view.documents.single()
        assertTrue(doc.missing)
        assertEquals("", doc.content)
    }

    @Test
    fun get_reports_an_unreachable_agent_and_unknown_node() = runTest {
        val f = fixture()
        f.files.reachable = false
        assertTrue(f.get(nodeId.value) is GetNodeConfigResult.AgentUnreachable)
        assertEquals(GetNodeConfigResult.NotFound, f.get("00000000-0000-4000-8000-000000000000"))
    }

    @Test
    fun save_writes_the_file_and_restarts_the_node() = runTest {
        val f = fixture()
        val r = f.save(nodeId.value, confirm = true, restart = true, documents = listOf(SaveNodeConfigDocument("main", "node.listen.port = 18889\nnode.maxConnections = 99\n")))
        val saved = r as SaveNodeConfigResult.Saved
        assertEquals(listOf(confPath), saved.written)
        assertTrue(saved.restarted)
        assertTrue(f.files.files[confPath]!!.contains("node.maxConnections = 99"))
        assertEquals(listOf(nodeId.value), f.restarts)
    }

    @Test
    fun save_restores_locked_ports_that_the_operator_changed() = runTest {
        val f = fixture()
        f.save(nodeId.value, confirm = true, restart = false, documents = listOf(SaveNodeConfigDocument("main", "node.listen.port = 9999\nnode.maxConnections = 99\n")))
        val written = f.files.files[confPath]!!
        assertFalse(written.contains("9999"), written)
        assertTrue(written.contains("18889"), written)
        assertTrue(written.contains("node.maxConnections = 99"), written)
    }

    @Test
    fun save_without_restart_does_not_touch_the_node() = runTest {
        val f = fixture()
        val saved = f.save(nodeId.value, confirm = true, restart = false, documents = listOf(SaveNodeConfigDocument("main", "node.listen.port = 18889\n"))) as SaveNodeConfigResult.Saved
        assertFalse(saved.restarted)
        assertTrue(f.restarts.isEmpty())
    }

    @Test
    fun a_failed_restart_is_reported_but_the_config_stays_saved() = runTest {
        val f = fixture(restartError = "port 18889 busy")
        val saved = f.save(nodeId.value, confirm = true, restart = true, documents = listOf(SaveNodeConfigDocument("main", "node.listen.port = 18889\n"))) as SaveNodeConfigResult.Saved
        assertFalse(saved.restarted)
        assertTrue(saved.message.contains("port 18889 busy"), saved.message)
        assertNotNull(f.files.files[confPath])
    }

    @Test
    fun save_validates_the_request() = runTest {
        val f = fixture()
        val doc = SaveNodeConfigDocument("main", "node.listen.port = 18889\n")
        assertEquals(SaveNodeConfigResult.ConfirmRequired, f.save(nodeId.value, confirm = false, restart = true, documents = listOf(doc)))
        assertEquals(SaveNodeConfigResult.NoDocuments, f.save(nodeId.value, confirm = true, restart = true, documents = emptyList()))
        assertEquals(SaveNodeConfigResult.UnknownDocument("nope"), f.save(nodeId.value, true, true, listOf(SaveNodeConfigDocument("nope", "x"))))
        assertEquals(SaveNodeConfigResult.NotWritable("launch"), f.save(nodeId.value, true, true, listOf(SaveNodeConfigDocument("launch", "{}"))))
        assertEquals(SaveNodeConfigResult.EmptyContent("main"), f.save(nodeId.value, true, true, listOf(SaveNodeConfigDocument("main", "  \n"))))
        assertEquals(SaveNodeConfigResult.NotFound, f.save("00000000-0000-4000-8000-000000000000", true, true, listOf(doc)))
        assertTrue(f.files.writes.isEmpty(), "nothing is written for rejected requests")
    }

    @Test
    fun save_reports_an_unreachable_agent() = runTest {
        val f = fixture()
        f.files.reachable = false
        val r = f.save(nodeId.value, true, true, listOf(SaveNodeConfigDocument("main", "node.listen.port = 18889\n")))
        assertTrue(r is SaveNodeConfigResult.AgentUnreachable)
        assertTrue(f.restarts.isEmpty())
    }
}
