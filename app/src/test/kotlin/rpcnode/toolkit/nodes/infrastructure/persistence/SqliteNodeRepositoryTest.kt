package rpcnode.toolkit.nodes.infrastructure.persistence

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.infrastructure.persistence.SqliteServerRepository
import rpcnode.toolkit.shared.infrastructure.persistence.ToolkitDatabase

class SqliteNodeRepositoryTest
{
    @Test
    fun insert_then_find_by_server_network_env() = runTest {
        val dir = Files.createTempDirectory("nodes")
        val db = ToolkitDatabase(dir.resolve("toolkit.db"))
        val servers = SqliteServerRepository(db)
        val nodes = SqliteNodeRepository(db)
        val serverId = ServerId.parse("srv-1")!!
        servers.insert(
            Server(
                id = serverId,
                name = "box",
                agentUrl = "http://127.0.0.1:38990",
                createdAt = "t",
                updatedAt = "t",
            ),
        )

        val node = Node(
            id = NodeId.parse("11111111-1111-4111-8111-111111111111")!!,
            serverId = serverId,
            name = "TRON mainnet",
            network = NetworkId.TRON,
            env = EnvId.MAINNET,
            status = NodeStatus.AWAITING_PORTS,
            createdAt = "t",
            updatedAt = "t",
        )
        nodes.insert(node)

        val found = nodes.findByServerNetworkEnv(serverId, NetworkId.TRON, EnvId.MAINNET)
        assertEquals(node.id, found?.id)
        assertEquals(NodeStatus.AWAITING_PORTS, found?.status)
        assertEquals(1, nodes.list().size)
        assertNull(nodes.findByServerNetworkEnv(serverId, NetworkId.TRON, EnvId.SHASTA))
    }

    @Test
    fun delete_removes_the_row() = runTest {
        val dir = Files.createTempDirectory("nodes-delete")
        val db = ToolkitDatabase(dir.resolve("toolkit.db"))
        val servers = SqliteServerRepository(db)
        val nodes = SqliteNodeRepository(db)
        val serverId = ServerId.parse("srv-1")!!
        servers.insert(
            Server(
                id = serverId,
                name = "box",
                agentUrl = "http://127.0.0.1:38990",
                createdAt = "t",
                updatedAt = "t",
            ),
        )
        val node = Node(
            id = NodeId.parse("22222222-2222-4222-8222-222222222222")!!,
            serverId = serverId,
            name = "TRON mainnet",
            network = NetworkId.TRON,
            env = EnvId.MAINNET,
            createdAt = "t",
            updatedAt = "t",
        )
        nodes.insert(node)

        assertEquals(true, nodes.delete(node.id))
        assertNull(nodes.findById(node.id))
        assertEquals(false, nodes.delete(node.id))
    }

    @Test
    fun save_disk_layout_persists_json() = runTest {
        val dir = Files.createTempDirectory("nodes-layout")
        val db = ToolkitDatabase(dir.resolve("toolkit.db"))
        val servers = SqliteServerRepository(db)
        val nodes = SqliteNodeRepository(db)
        val serverId = ServerId.parse("srv-1")!!
        servers.insert(
            Server(
                id = serverId,
                name = "box",
                agentUrl = "http://127.0.0.1:38990",
                createdAt = "t",
                updatedAt = "t",
            ),
        )
        val nodeId = NodeId.parse("33333333-3333-4333-8333-333333333333")!!
        val node = Node(
            id = nodeId,
            serverId = serverId,
            name = "TRON mainnet",
            network = NetworkId.TRON,
            env = EnvId.MAINNET,
            createdAt = "t",
            updatedAt = "t",
        )
        nodes.insert(node)

        val layout = """{"strategy":"jbod_2","network":"tron","env":"mainnet","roles":[]}"""
        assertEquals(true, nodes.saveDiskLayout(nodeId, layout, "t2"))

        val loaded = nodes.findById(nodeId)
        assertEquals(layout, loaded?.diskLayoutJson)
    }

    private suspend fun repo(): Triple<SqliteNodeRepository, ToolkitDatabase, ServerId>
    {
        val db = ToolkitDatabase(Files.createTempDirectory("nodes-lifecycle").resolve("toolkit.db"))
        val serverId = ServerId.parse("srv-life")!!
        SqliteServerRepository(db).insert(Server(id = serverId, name = "box", agentUrl = "http://127.0.0.1:1", createdAt = "t", updatedAt = "t"))
        return Triple(SqliteNodeRepository(db), db, serverId)
    }

    private fun node(n: Int, serverId: ServerId, env: EnvId, status: NodeStatus = NodeStatus.AWAITING_PORTS) = Node(
        id = NodeId.parse("2222222$n-2222-4222-8222-222222222222")!!,
        serverId = serverId,
        name = "n$n",
        network = NetworkId.TRON,
        env = env,
        status = status,
        createdAt = "created",
        updatedAt = "created",
    )

    @Test
    fun install_and_synced_dates_are_written_once_by_the_status_changes() = runTest {
        val (nodes, _, serverId) = repo()
        val n = node(1, serverId, EnvId.MAINNET)
        nodes.insert(n)
        assertEquals("", nodes.findById(n.id)!!.installStartedAt)

        nodes.updateStatus(n.id, NodeStatus.AWAITING_PORTS, "2026-10-16T10:00:00Z")
        assertEquals("", nodes.findById(n.id)!!.installStartedAt, "ports not confirmed yet")

        nodes.updateStatus(n.id, NodeStatus.parse("host_deps_running"), "2026-10-16T10:05:00Z")
        nodes.updateStatus(n.id, NodeStatus.SYNC, "2026-10-16T10:30:00Z")
        val syncing = nodes.findById(n.id)!!
        assertEquals("2026-10-16T10:05:00Z", syncing.installStartedAt, "the first status after the ports step")
        assertEquals("", syncing.syncedAt)

        nodes.updateStatus(n.id, NodeStatus.ACTIVE, "2026-10-16T12:00:00Z")
        nodes.updateStatus(n.id, NodeStatus.SYNC, "2026-10-16T13:00:00Z")
        nodes.updateStatus(n.id, NodeStatus.ACTIVE, "2026-10-16T14:00:00Z")
        val after = nodes.findById(n.id)!!
        assertEquals("2026-10-16T10:05:00Z", after.installStartedAt, "never moves")
        assertEquals("2026-10-16T12:00:00Z", after.syncedAt, "the first time it was active")
        assertEquals("2026-10-16T14:00:00Z", after.updatedAt)
    }

    @Test
    fun the_migration_gives_existing_nodes_today_and_leaves_nodes_that_have_not_started_blank() = runTest {
        val (nodes, db, serverId) = repo()
        val fresh = node(1, serverId, EnvId.MAINNET, NodeStatus.AWAITING_PORTS)
        val running = node(2, serverId, EnvId.SHASTA, NodeStatus.SYNC)
        val done = node(3, serverId, EnvId.NILE, NodeStatus.ACTIVE)
        listOf(fresh, running, done).forEach { nodes.insert(it) }
        val sql = checkNotNull(javaClass.classLoader.getResourceAsStream("db/migration/V18__nodes_install_started.sql")).readBytes().toString(Charsets.UTF_8)
        val statements = sql.lines().filterNot { it.trim().startsWith("--") }.joinToString("\n").split(";").map { it.trim() }.filter { it.isNotEmpty() }
        org.jetbrains.exposed.sql.transactions.transaction(db.database) { statements.forEach { exec(it) } }

        val today = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")
        assertEquals("", nodes.findById(fresh.id)!!.installStartedAt, "waiting for ports: nothing started")
        assertTrue(today.matches(nodes.findById(running.id)!!.installStartedAt), nodes.findById(running.id)!!.installStartedAt)
        assertEquals("", nodes.findById(running.id)!!.syncedAt, "not active yet: not synced")
        assertTrue(today.matches(nodes.findById(done.id)!!.syncedAt))
        // running the statements again changes nothing: the dates are only filled where blank
        val first = nodes.findById(done.id)!!.installStartedAt
        org.jetbrains.exposed.sql.transactions.transaction(db.database) { statements.forEach { exec(it) } }
        assertEquals(first, nodes.findById(done.id)!!.installStartedAt)
    }
}

