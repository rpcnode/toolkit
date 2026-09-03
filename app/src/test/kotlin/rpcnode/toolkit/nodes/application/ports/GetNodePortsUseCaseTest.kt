package rpcnode.toolkit.nodes.application.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.FakeClientProgramCatalog
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.model.ClientVersionSource
import rpcnode.toolkit.clients.domain.model.ProgramPort
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

class GetNodePortsUseCaseTest
{
    private val server = Server(
        id = ServerId.parse("srv-1")!!,
        name = "box",
        agentUrl = "http://10.0.0.5:38990",
        agentKey = "key-1",
        createdAt = "t",
        updatedAt = "t",
    )
    private val node = Node(
        id = NodeId.parse("11111111-1111-4111-8111-111111111111")!!,
        serverId = server.id,
        name = "TRON mainnet",
        network = NetworkId.TRON,
        env = EnvId.MAINNET,
        createdAt = "t",
        updatedAt = "t",
    )
    private val spec = ClientProgramSpec(
        network = NetworkId.TRON,
        env = EnvId.MAINNET,
        programId = "FullNode.jar",
        source = ClientVersionSource.Pinned(version = "1", tag = "v1", label = "test"),
        ports = listOf(
            ProgramPort(role = "p2p", port = 18888, label = "P2P"),
            ProgramPort(role = "http_fullnode", port = 18090, label = "HTTP API (fullnode)"),
        ),
    )

    @Test
    fun returns_catalog_ports_without_live_status() = runTest {
        val useCase = GetNodePortsUseCase(
            nodes = FakeNodeRepository(listOf(node)),
            servers = FakeServerRepository(listOf(server)),
            catalog = FakeClientProgramCatalog(listOf(spec)),
        )

        val result = assertIs<NodePortsResult.Ok>(useCase(node.id.value))

        assertEquals(2, result.ports.size)
        assertNull(result.ports[0].free)
        assertNull(result.ports[1].holder)
        assertEquals("http://10.0.0.5:18090", result.endpoint)
    }
}
