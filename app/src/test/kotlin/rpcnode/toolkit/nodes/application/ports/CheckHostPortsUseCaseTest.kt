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
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.application.probe.AgentPortCheck
import rpcnode.toolkit.servers.application.probe.CheckAgentPorts
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

class CheckHostPortsUseCaseTest
{
    private val server = Server(
        id = ServerId.parse("srv-1")!!,
        name = "box",
        agentUrl = "http://10.0.0.5:38990",
        agentKey = "key-1",
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
    fun merges_catalog_ports_with_agent_check_and_builds_the_endpoint() = runTest {
        val checkAgentPorts = CheckAgentPorts { agentUrl, token, ports ->
            assertEquals("http://10.0.0.5:38990", agentUrl)
            assertEquals("key-1", token)
            assertEquals(listOf(18888, 18090), ports)
            listOf(
                AgentPortCheck(port = 18888, free = true),
                AgentPortCheck(port = 18090, free = false, holder = "FullNode.jar"),
            )
        }
        val useCase = CheckHostPortsUseCase(
            servers = FakeServerRepository(listOf(server)),
            catalog = FakeClientProgramCatalog(listOf(spec)),
            checkAgentPorts = checkAgentPorts,
        )

        val result = assertIs<NodePortsResult.Ok>(useCase(server.id.value, NetworkId.TRON, EnvId.MAINNET))

        assertEquals(
            listOf(
                NodePort(role = "p2p", port = 18888, label = "P2P", free = true),
                NodePort(role = "http_fullnode", port = 18090, label = "HTTP API (fullnode)", free = false, holder = "FullNode.jar"),
            ),
            result.ports,
        )
        assertEquals("http://10.0.0.5:18090", result.endpoint)
    }

    @Test
    fun unreachable_agent_still_returns_catalog_ports_without_status() = runTest {
        val useCase = CheckHostPortsUseCase(
            servers = FakeServerRepository(listOf(server)),
            catalog = FakeClientProgramCatalog(listOf(spec)),
            checkAgentPorts = CheckAgentPorts { _, _, _ -> null },
        )

        val result = assertIs<NodePortsResult.AgentUnreachable>(
            useCase(server.id.value, NetworkId.TRON, EnvId.MAINNET),
        )

        assertEquals(2, result.ports.size)
        assertNull(result.ports[0].free)
        assertEquals("http://10.0.0.5:18090", result.endpoint)
    }

    @Test
    fun network_env_with_no_catalog_ports_is_reported_as_no_ports() = runTest {
        val useCase = CheckHostPortsUseCase(
            servers = FakeServerRepository(listOf(server)),
            catalog = FakeClientProgramCatalog(emptyList()),
            checkAgentPorts = CheckAgentPorts { _, _, _ -> null },
        )

        assertEquals(
            NodePortsResult.NoPorts,
            useCase(server.id.value, NetworkId.TRON, EnvId.MAINNET),
        )
    }

    @Test
    fun missing_server_is_reported() = runTest {
        val useCase = CheckHostPortsUseCase(
            servers = FakeServerRepository(),
            catalog = FakeClientProgramCatalog(listOf(spec)),
            checkAgentPorts = CheckAgentPorts { _, _, _ -> null },
        )

        assertEquals(
            NodePortsResult.ServerNotFound,
            useCase(server.id.value, NetworkId.TRON, EnvId.MAINNET),
        )
    }
}
