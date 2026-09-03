package rpcnode.toolkit.nodes.application.remove

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

class RemoveNodeUseCaseTest
{
    private val serverId = ServerId.parse("srv-1")!!
    private val node = Node(
        id = NodeId.parse("11111111-1111-4111-8111-111111111111")!!,
        serverId = serverId,
        name = "TRON mainnet",
        network = NetworkId.TRON,
        env = EnvId.MAINNET,
        createdAt = "t",
        updatedAt = "t",
        diskLayoutJson = """{"roles":{"fullnode":{"path":"/data/tron/mainnet/fullnode"}}}""",
    )
    private val server = Server(
        id = serverId,
        name = "Local",
        agentUrl = "http://127.0.0.1:9",
        agentKey = "k",
        createdAt = "t",
        updatedAt = "t",
    )

    @Test
    fun panel_mode_drops_the_row() = runTest {
        val nodes = FakeNodeRepository(listOf(node))
        val useCase = RemoveNodeUseCase(nodes)

        val result = useCase(node.id.value, RemoveNodeMode.PANEL)

        assertIs<RemoveNodeResult.Removed>(result)
        assertEquals(node.id, result.node.id)
        assertEquals(RemoveNodeMode.PANEL, result.mode)
        assertNull(nodes.findById(node.id))
    }

    @Test
    fun wipe_mode_calls_host_then_drops_row() = runTest {
        val nodes = FakeNodeRepository(listOf(node))
        val servers = FakeServerRepository(listOf(server))
        var wiped: Boolean? = null
        val useCase = RemoveNodeUseCase(
            nodes = nodes,
            servers = servers,
            resolveDestDir = { "/data/tron/mainnet/fullnode" },
            removeOnHost = RemoveNodeOnHost { _, _, command ->
                wiped = command.wipeData
                RemoveNodeOnHostResult.Ok
            },
        )

        val result = useCase(node.id.value, RemoveNodeMode.WIPE)

        assertIs<RemoveNodeResult.Removed>(result)
        assertEquals(RemoveNodeMode.WIPE, result.mode)
        assertEquals(true, wiped)
        assertNull(nodes.findById(node.id))
    }

    @Test
    fun agents_mode_keeps_data_flag_false() = runTest {
        val nodes = FakeNodeRepository(listOf(node))
        val servers = FakeServerRepository(listOf(server))
        var wiped: Boolean? = null
        val useCase = RemoveNodeUseCase(
            nodes = nodes,
            servers = servers,
            resolveDestDir = { "/data/tron/mainnet/fullnode" },
            removeOnHost = RemoveNodeOnHost { _, _, command ->
                wiped = command.wipeData
                RemoveNodeOnHostResult.Ok
            },
        )

        val result = useCase(node.id.value, RemoveNodeMode.AGENTS)

        assertIs<RemoveNodeResult.Removed>(result)
        assertEquals(false, wiped)
        assertNull(nodes.findById(node.id))
    }

    @Test
    fun wipe_without_dest_dir_fails() = runTest {
        val nodes = FakeNodeRepository(listOf(node.copy(diskLayoutJson = "")))
        val useCase = RemoveNodeUseCase(
            nodes = nodes,
            servers = FakeServerRepository(listOf(server)),
            resolveDestDir = { null },
            removeOnHost = RemoveNodeOnHost { _, _, _ -> RemoveNodeOnHostResult.Ok },
        )

        val result = useCase(node.id.value, RemoveNodeMode.WIPE)

        assertIs<RemoveNodeResult.Failed>(result)
        assertEquals("no_disk_layout", result.error)
        assertEquals(node.id, nodes.findById(node.id)!!.id)
    }

    @Test
    fun unknown_id_is_not_found() = runTest {
        val useCase = RemoveNodeUseCase(FakeNodeRepository())

        val result = useCase("missing", RemoveNodeMode.PANEL)

        assertEquals(RemoveNodeResult.NotFound, result)
    }
}
