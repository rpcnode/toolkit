package rpcnode.toolkit.networks.application.connect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

class ListL1ParentChoicesUseCaseTest
{
    @Test
    fun includes_public_and_same_host_active_eth() = runTest {
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
            name = "Ethereum Sepolia syncing",
            status = NodeStatus.SYNC,
        )
        val uc = ListL1ParentChoicesUseCase(
            facts = YamlNetworkFactsRepository(),
            nodes = FakeNodeRepository(listOf(eth, syncOnly)),
            servers = FakeServerRepository(listOf(server)),
        )
        val got = uc("base", "sepolia", server.id.value)
        assertIs<ListL1ParentChoicesUseCase.Result.Ready>(got)
        assertEquals("sepolia", got.value.l1Env)
        assertTrue(got.value.pickHelp!!.contains("Public"))
        assertEquals("public", got.value.choices.first().kind)
        assertTrue(got.value.choices.first().rpc.contains("sepolia"))
        assertTrue(got.value.choices.first().label.contains("http"))
        assertTrue(
            got.value.choices.any {
                it.kind == "node" && it.sameHost && it.rpc == "http://127.0.0.1:8546"
            },
        )
        assertTrue(got.value.choices.none { it.nodeId == "eth-sync" })
    }

    @Test
    fun remote_eth_uses_agent_host() = runTest {
        val local = Server(
            id = ServerId.parse("srv-local")!!,
            name = "local",
            agentUrl = "http://10.0.0.1:9090",
            createdAt = "t",
            updatedAt = "t",
        )
        val remote = Server(
            id = ServerId.parse("srv-remote")!!,
            name = "remote",
            agentUrl = "http://95.81.240.173:9090",
            createdAt = "t",
            updatedAt = "t",
        )
        val eth = Node(
            id = NodeId.parse("eth-r")!!,
            serverId = remote.id,
            name = "eth mainnet",
            network = NetworkId.ETHEREUM,
            env = EnvId.parse("mainnet")!!,
            status = NodeStatus.ACTIVE,
            createdAt = "t",
            updatedAt = "t",
        )
        val uc = ListL1ParentChoicesUseCase(
            facts = YamlNetworkFactsRepository(),
            nodes = FakeNodeRepository(listOf(eth)),
            servers = FakeServerRepository(listOf(local, remote)),
        )
        val got = uc("arb", "mainnet", local.id.value)
        assertIs<ListL1ParentChoicesUseCase.Result.Ready>(got)
        val nodeChoice = got.value.choices.single { it.kind == "node" }
        assertEquals("http://95.81.240.173:8545", nodeChoice.rpc)
        assertEquals("http://95.81.240.173:5052", nodeChoice.beacon)
        assertEquals(false, nodeChoice.sameHost)
    }
}
