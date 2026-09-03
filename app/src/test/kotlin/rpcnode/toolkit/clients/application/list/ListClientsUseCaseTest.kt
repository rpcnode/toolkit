package rpcnode.toolkit.clients.application.list

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.FakeClientProgramCatalog
import rpcnode.toolkit.clients.FakeClientVersionRepository
import rpcnode.toolkit.clients.domain.model.ClientArtifactRole
import rpcnode.toolkit.clients.domain.model.ClientArtifactSpec
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.model.ClientStatus
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.clients.domain.model.ClientVersionSource

private fun program(network: NetworkId, env: EnvId, id: String) = ClientProgramSpec(
    network = network,
    env = env,
    programId = id,
    source = ClientVersionSource.Pinned(version = "1.0", tag = "v1.0", label = "test"),
    artifacts = listOf(ClientArtifactSpec("bin", ClientArtifactRole.ARTIFACT, "https://example.com/{version}")),
)

class ListClientsUseCaseTest
{
    @Test
    fun probe_only_rows_never_show_up_in_the_list() = runTest {
        val repo = FakeClientVersionRepository(
            seed = listOf(ClientVersionPin(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin", latestVersion = "29.4")),
        )
        val catalog = FakeClientProgramCatalog(listOf(program(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin")))
        val result = ListClientsUseCase(repo, catalog)()
        assertEquals(0, result.rows.size)
    }

    @Test
    fun a_synced_network_env_fills_in_missing_sibling_programs() = runTest {
        val repo = FakeClientVersionRepository(
            seed = listOf(ClientVersionPin(NetworkId.TRON, EnvId.MAINNET, "geth", currentVersion = "1.0", latestVersion = "1.0")),
        )
        val catalog = FakeClientProgramCatalog(
            listOf(
                program(NetworkId.TRON, EnvId.MAINNET, "geth"),
                program(NetworkId.TRON, EnvId.MAINNET, "lighthouse"),
            ),
        )
        val result = ListClientsUseCase(repo, catalog)()
        assertEquals(2, result.rows.size)
        val lighthouse = result.rows.single { it.program == "lighthouse" }
        assertEquals(ClientStatus.MISSING, lighthouse.status)
        assertEquals(1, result.stats.missing)
    }

    @Test
    fun stats_count_every_status_bucket() = runTest {
        val repo = FakeClientVersionRepository(
            seed = listOf(
                ClientVersionPin(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin", currentVersion = "1.0", latestVersion = "1.1"),
            ),
        )
        val catalog = FakeClientProgramCatalog(listOf(program(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin")))
        val result = ListClientsUseCase(repo, catalog)()
        assertEquals(1, result.stats.total)
        assertEquals(1, result.stats.stale)
        assertEquals(0, result.stats.fail)
        assertEquals(0, result.stats.missing)
    }
}
