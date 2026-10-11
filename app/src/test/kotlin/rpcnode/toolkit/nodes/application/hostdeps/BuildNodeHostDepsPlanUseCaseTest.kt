package rpcnode.toolkit.nodes.application.hostdeps

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
import rpcnode.toolkit.servers.domain.model.ServerId

class BuildNodeHostDepsPlanUseCaseTest
{
    private val nodeId = NodeId.parse("55555555-5555-4555-8555-555555555555")!!

    private suspend fun plan(network: NetworkId, env: EnvId): NodeHostDepsPlan
    {
        val node = Node(
            id = nodeId,
            serverId = ServerId.parse("srv-1")!!,
            name = "node",
            network = network,
            env = env,
            status = NodeStatus.parse("awaiting_ports"),
            createdAt = "t",
            updatedAt = "t",
        )
        val useCase = BuildNodeHostDepsPlanUseCase(
            nodes = FakeNodeRepository(listOf(node)),
            facts = YamlNetworkFactsRepository(),
            catalog = YamlClientProgramCatalog(),
        )
        val result = useCase(nodeId.value)
        return assertIs<GetNodeHostDepsPlanResult.Ok>(result).plan
    }

    @Test
    fun ton_needs_clang_21_from_the_llvm_repo() = runTest {
        val deps = plan(NetworkId.TON, EnvId.TESTNET).deps
        val clang = deps.single { it.id == "clang-21" }
        assertEquals("clang", clang.kind)
        assertEquals("clang-21", clang.name)
        assertEquals("package", deps.single { it.id == "git" }.kind)
    }

    @Test
    fun solana_keeps_plain_clang_package() = runTest {
        val deps = plan(NetworkId.SOLANA, EnvId.MAINNET).deps
        assertEquals("package", deps.single { it.id == "clang" }.kind)
    }
}
