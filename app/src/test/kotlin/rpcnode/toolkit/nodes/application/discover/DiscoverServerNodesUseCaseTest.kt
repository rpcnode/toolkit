package rpcnode.toolkit.nodes.application.discover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.Chain
import rpcnode.toolkit.catalog.domain.Env
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkCatalog
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

class DiscoverServerNodesUseCaseTest
{
    private val dash = Chain(
        id = NetworkId.parse("dash")!!,
        label = "Dash",
        envs = listOf(Env(id = EnvId.parse("mainnet")!!, displayName = "Mainnet")),
    )
    private val ltc = Chain(
        id = NetworkId.parse("ltc")!!,
        label = "Litecoin",
        envs = listOf(Env(id = EnvId.parse("mainnet")!!, displayName = "Mainnet")),
    )
    private val catalog = object : NetworkCatalog
    {
        override fun find(id: NetworkId): Chain? =
            listOf(dash, ltc).firstOrNull { it.id == id }

        override fun all(): List<Chain> = listOf(dash, ltc)
    }

    @Test
    fun filters_unknown_and_already_tracked() = runTest {
        val serverId = ServerId.parse("srv-1")!!
        val servers = FakeServerRepository(
            listOf(
                Server(
                    id = serverId,
                    name = "box",
                    agentUrl = "http://10.0.0.5:48990",
                    agentKey = "tok",
                    agentVersion = "0.1.0",
                    createdAt = "t",
                    updatedAt = "t",
                ),
            ),
        )
        val nodes = FakeNodeRepository(
            listOf(
                Node(
                    id = NodeId.generate(),
                    serverId = serverId,
                    network = dash.id,
                    env = EnvId.parse("mainnet")!!,
                    name = "dash",
                    status = NodeStatus.AWAITING_PORTS,
                    createdAt = "t",
                    updatedAt = "t",
                ),
            ),
        )
        val useCase = DiscoverServerNodesUseCase(
            servers = servers,
            nodes = nodes,
            catalog = catalog,
            fetchOnHost = FetchHostInstalledNodes { _, _ ->
                FetchHostInstalledNodesResult.Ok(
                    listOf(
                        HostInstalledNode(network = "dash", env = "mainnet", status = "present"),
                        HostInstalledNode(network = "ltc", env = "mainnet", publicPort = 9332),
                        HostInstalledNode(network = "unknown", env = "mainnet"),
                    ),
                )
            },
        )
        val result = useCase("srv-1")
        assertTrue(result is DiscoverServerNodesResult.Ok)
        val items = (result as DiscoverServerNodesResult.Ok).items
        assertEquals(1, items.size)
        assertEquals("ltc", items[0].network)
        assertEquals("mainnet", items[0].env)
        assertEquals("Litecoin", items[0].label)
        assertEquals(9332, items[0].publicPort)
    }
}
