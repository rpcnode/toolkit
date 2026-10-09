package rpcnode.toolkit.networks.application.connect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

class ListEthereumNodesUseCaseTest
{
    @Test
    fun returns_publicTip_and_active_nodes() = runTest {
        val server = Server(
            id = ServerId.parse("srv-1")!!,
            name = "host-a",
            agentUrl = "http://10.0.0.5:9090",
            createdAt = "t",
            updatedAt = "t",
        )
        val eth = Node(
            id = NodeId.parse("eth-1")!!,
            serverId = server.id,
            name = "Ethereum Sepolia",
            network = NetworkId.ETHEREUM,
            env = EnvId.parse("sepolia")!!,
            status = NodeStatus.ACTIVE,
            createdAt = "t",
            updatedAt = "t",
        )
        val syncOnly = eth.copy(
            id = NodeId.parse("eth-sync")!!,
            name = "syncing",
            status = NodeStatus.SYNC,
        )
        val uc = ListEthereumNodesUseCase(
            facts = YamlNetworkFactsRepository(),
            nodes = FakeNodeRepository(listOf(eth, syncOnly)),
            servers = FakeServerRepository(listOf(server)),
        )
        val got = uc(envRaw = "sepolia", statusRaw = "active", serverId = server.id.value)
        assertIs<ListEthereumNodesUseCase.Result.Ready>(got)
        val pub = assertNotNull(got.value.public)
        assertTrue(pub.rpc.contains("sepolia"))
        assertTrue(pub.beacon.contains("beacon"))
        assertEquals(1, got.value.items.size)
        assertEquals("eth-1", got.value.items.single().id)
        assertEquals(pub.rpc, got.value.items.single().publicEndpoint)
        assertEquals("http://127.0.0.1:8546", got.value.items.single().rpc)
    }
}
