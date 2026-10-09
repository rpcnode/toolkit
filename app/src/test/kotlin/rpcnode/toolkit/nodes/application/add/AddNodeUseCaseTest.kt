package rpcnode.toolkit.nodes.application.add

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.FakeClientVersionRepository
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.networks.domain.model.NetworkFacts
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.nodes.domain.repository.NodeInsertResult
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

class AddNodeUseCaseTest
{
    private val catalog = YamlNetworkFactsRepository()
    private val clock = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC)
    private val server = Server(
        id = ServerId.parse("srv-1")!!,
        name = "box",
        agentUrl = "http://127.0.0.1:38990",
        createdAt = "t",
        updatedAt = "t",
    )

    private fun useCase(
        nodes: FakeNodeRepository = FakeNodeRepository(),
        servers: FakeServerRepository = FakeServerRepository(listOf(server)),
        clients: FakeClientVersionRepository = FakeClientVersionRepository(
            listOf(
                ClientVersionPin(
                    network = NetworkId.TRON,
                    env = EnvId.MAINNET,
                    program = "FullNode.jar",
                    currentVersion = "v1",
                ),
            ),
        ),
        facts: NetworkFactsRepository = catalog,
    ) = AddNodeUseCase(
        nodes = nodes,
        servers = servers,
        catalog = catalog,
        clients = clients,
        facts = facts,
        clock = clock,
        newId = { NodeId.parse("node-1")!! },
    )

    @Test
    fun persists_awaiting_ports_after_network_env_server() = runTest {
        val nodes = FakeNodeRepository()
        val result = useCase(nodes = nodes)("srv-1", "tron", "mainnet")
        val created = assertIs<AddNodeResult.Created>(result)
        assertEquals("node-1", created.node.id.value)
        assertEquals(NetworkId.TRON, created.node.network)
        assertEquals(EnvId.MAINNET, created.node.env)
        assertEquals(NodeStatus.AWAITING_PORTS, created.node.status)
        assertEquals(0, created.node.publicPort)
        assertEquals("TRON mainnet", created.node.name)
        assertEquals("v1", created.node.clientVersion)
        assertEquals("v1", created.node.clientLatest)
        assertEquals(false, created.node.clientUpdateAvailable)
        assertEquals(1, nodes.list().size)
    }

    @Test
    fun persists_client_version_from_pin_at_add() = runTest {
        val clients = FakeClientVersionRepository(
            listOf(
                ClientVersionPin(
                    network = NetworkId.TRON,
                    env = EnvId.MAINNET,
                    program = "FullNode.jar",
                    currentVersion = "4.8.1",
                    latestVersion = "4.8.2",
                ),
            ),
        )
        val created = assertIs<AddNodeResult.Created>(
            useCase(clients = clients)("srv-1", "tron", "mainnet"),
        )
        assertEquals("4.8.1", created.node.clientVersion)
        assertEquals("4.8.2", created.node.clientLatest)
        assertEquals(true, created.node.clientUpdateAvailable)
    }

    @Test
    fun unknown_network_is_rejected() = runTest {
        assertIs<AddNodeResult.UnknownNetwork>(useCase()("srv-1", "nope", "mainnet"))
    }

    @Test
    fun unknown_env_is_rejected() = runTest {
        assertIs<AddNodeResult.UnknownEnv>(useCase()("srv-1", "tron", "not-an-env"))
    }

    @Test
    fun missing_server_is_rejected() = runTest {
        assertIs<AddNodeResult.ServerNotFound>(useCase()("missing", "tron", "mainnet"))
    }

    @Test
    fun missing_client_is_rejected() = runTest {
        val result = useCase(clients = FakeClientVersionRepository())("srv-1", "tron", "mainnet")
        assertIs<AddNodeResult.NoClient>(result)
    }

    @Test
    fun duplicate_server_network_env_is_rejected() = runTest {
        val existing = Node(
            id = NodeId.parse("already")!!,
            serverId = server.id,
            name = "old",
            network = NetworkId.TRON,
            env = EnvId.MAINNET,
            createdAt = "t",
            updatedAt = "t",
        )
        val result = useCase(nodes = FakeNodeRepository(listOf(existing)))("srv-1", "tron", "mainnet")
        val hit = assertIs<AddNodeResult.AlreadyExists>(result)
        assertEquals("already", hit.existing.id.value)
    }

    @Test
    fun one_env_per_host_blocks_a_second_env() = runTest {
        val occupied = Node(
            id = NodeId.parse("other")!!,
            serverId = server.id,
            name = "old",
            network = NetworkId.TRON,
            env = EnvId.SHASTA,
            createdAt = "t",
            updatedAt = "t",
        )
        val clients = FakeClientVersionRepository(
            listOf(
                ClientVersionPin(NetworkId.TRON, EnvId.MAINNET, "FullNode.jar", currentVersion = "v1"),
            ),
        )
        val facts = object : NetworkFactsRepository
        {
            override fun factsFor(network: NetworkId) = NetworkFacts(oneEnvPerHost = true)
        }
        val result = useCase(
            nodes = FakeNodeRepository(listOf(occupied)),
            clients = clients,
            facts = facts,
        )("srv-1", "tron", "mainnet")
        val blocked = assertIs<AddNodeResult.OneEnvPerHost>(result)
        assertEquals(EnvId.SHASTA, blocked.occupied.env)
    }

    @Test
    fun insert_unique_race_is_already_exists() = runTest {
        val existing = Node(
            id = NodeId.parse("already")!!,
            serverId = server.id,
            name = "old",
            network = NetworkId.TRON,
            env = EnvId.MAINNET,
            createdAt = "t",
            updatedAt = "t",
        )
        val nodes = FakeNodeRepository(listOf(existing), forcedInsert = NodeInsertResult.Duplicate)
        nodes.findMisses = 1
        val hit = assertIs<AddNodeResult.AlreadyExists>(useCase(nodes = nodes)("srv-1", "tron", "mainnet"))
        assertEquals("already", hit.existing.id.value)
    }

    @Test
    fun insert_duplicate_without_a_row_is_insert_failed() = runTest {
        val nodes = FakeNodeRepository(forcedInsert = NodeInsertResult.Duplicate)
        assertIs<AddNodeResult.InsertFailed>(useCase(nodes = nodes)("srv-1", "tron", "mainnet"))
    }
}
