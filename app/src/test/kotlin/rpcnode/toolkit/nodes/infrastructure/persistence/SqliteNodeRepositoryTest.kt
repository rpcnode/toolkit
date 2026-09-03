package rpcnode.toolkit.nodes.infrastructure.persistence

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
}
