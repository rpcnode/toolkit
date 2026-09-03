package rpcnode.toolkit.networks.application.setstatus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.networks.FakeNetworkRepository
import rpcnode.toolkit.networks.domain.model.NetworkStatus

class SetNetworkStatusUseCaseTest
{
    @Test
    fun enable_persists_ready_status_regardless_of_files_on_disk() = runTest {
        val repo = FakeNetworkRepository()
        val result = useCase(repo).invoke("bitcoin", "enable")
        val ok = assertIs<SetNetworkStatusResult.Ok>(result)
        assertEquals(NetworkId.BITCOIN, ok.network)
        assertEquals(NetworkStatus.READY, ok.status)
        assertEquals(NetworkStatus.READY, repo.list().single().status)
    }

    @Test
    fun skip_persists_skipped_status() = runTest {
        val repo = FakeNetworkRepository()
        val result = useCase(repo).invoke("bitcoin", "skip")
        val ok = assertIs<SetNetworkStatusResult.Ok>(result)
        assertEquals(NetworkStatus.SKIPPED, ok.status)
        assertEquals(NetworkStatus.SKIPPED, repo.list().single().status)
    }

    @Test
    fun pending_persists_pending_status() = runTest {
        val repo = FakeNetworkRepository()
        val result = useCase(repo).invoke("bitcoin", "pending")
        assertEquals(NetworkStatus.PENDING, (result as SetNetworkStatusResult.Ok).status)
    }

    @Test
    fun unknown_network_is_rejected() = runTest {
        val result = useCase().invoke("does-not-exist", "enable")
        assertIs<SetNetworkStatusResult.UnknownNetwork>(result)
    }

    @Test
    fun bad_action_is_rejected() = runTest {
        val result = useCase().invoke("bitcoin", "explode")
        assertIs<SetNetworkStatusResult.BadAction>(result)
    }

    private fun useCase(networkRepo: FakeNetworkRepository = FakeNetworkRepository()) =
        SetNetworkStatusUseCase(
            catalog = YamlNetworkFactsRepository(),
            networkRepo = networkRepo,
        )
}
