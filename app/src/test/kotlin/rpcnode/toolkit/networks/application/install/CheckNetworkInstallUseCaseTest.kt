package rpcnode.toolkit.networks.application.install

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.networks.application.ClientFilesReadyChecker

class CheckNetworkInstallUseCaseTest
{
    @Test
    fun files_ready_returns_files_ok() = runTest {
        val useCase = CheckNetworkInstallUseCase(
            catalog = YamlNetworkFactsRepository(),
            filesReady = ClientFilesReadyChecker { _, _ -> true },
        )
        val result = useCase("bitcoin")
        val ok = assertIs<CheckNetworkInstallResult.FilesOk>(result)
        assertEquals(NetworkId.BITCOIN, ok.network)
    }

    @Test
    fun files_missing_returns_client_required() = runTest {
        val useCase = CheckNetworkInstallUseCase(
            catalog = YamlNetworkFactsRepository(),
            filesReady = ClientFilesReadyChecker { _, _ -> false },
        )
        assertIs<CheckNetworkInstallResult.ClientRequired>(useCase("bitcoin"))
    }

    @Test
    fun unknown_network_is_rejected() = runTest {
        val useCase = CheckNetworkInstallUseCase(
            catalog = YamlNetworkFactsRepository(),
            filesReady = ClientFilesReadyChecker { _, _ -> true },
        )
        assertIs<CheckNetworkInstallResult.UnknownNetwork>(useCase("does-not-exist"))
    }
}
