package rpcnode.toolkit.nodes.application.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

class GetNodeDiagnosticsUseCaseTest
{
    private val nodeId = NodeId.parse("66666666-6666-4666-8666-666666666666")!!
    private val serverId = ServerId.parse("srv-1")!!

    private fun useCase(network: NetworkId, env: EnvId, sent: MutableList<NodeDiagnosticCheck>) = GetNodeDiagnosticsUseCase(
        nodes = FakeNodeRepository(
            listOf(
                Node(
                    id = nodeId, serverId = serverId, name = "n", network = network, env = env,
                    status = NodeStatus.parse("sync"), createdAt = "t", updatedAt = "t",
                ),
            ),
        ),
        servers = FakeServerRepository(
            listOf(Server(id = serverId, name = "box", agentUrl = "http://127.0.0.1:9", agentKey = "k", createdAt = "t", updatedAt = "t")),
        ),
        facts = YamlNetworkFactsRepository(),
        catalog = YamlClientProgramCatalog(),
        runOnHost = RunNodeDiagnosticsOnHost { _, _, _, checks ->
            sent += checks
            RunNodeDiagnosticsOnHostResult.Ok(checks.map { NodeDiagnosticItem(it.id, "ok", it.id) })
        },
    )

    @Test
    fun ton_checks_come_from_network_yml_with_the_env_p2p_port() = runTest {
        val sent = mutableListOf<NodeDiagnosticCheck>()
        val result = useCase(NetworkId.TON, EnvId.TESTNET, sent)(nodeId.value)
        assertIs<GetNodeDiagnosticsResult.Ok>(result)
        assertEquals(listOf("path_mtu", "nat", "ton_advertised_ip", "ton_catchup"), sent.map { it.id })
        val nat = sent.first { it.id == "nat" }
        assertEquals(31311, nat.port)
        assertEquals("udp", nat.protocol)
        assertEquals("12", sent.last().params["warnHours"])
    }

    @Test
    fun every_network_gets_the_path_mtu_check() = runTest {
        val sent = mutableListOf<NodeDiagnosticCheck>()
        val result = useCase(NetworkId.BITCOIN, EnvId.MAINNET, sent)(nodeId.value)
        assertIs<GetNodeDiagnosticsResult.Ok>(result)
        assertEquals(listOf("path_mtu"), sent.map { it.id })
    }
}
