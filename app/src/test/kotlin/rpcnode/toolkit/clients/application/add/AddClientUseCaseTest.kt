package rpcnode.toolkit.clients.application.add

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.clients.FakeClientProgramCatalog
import rpcnode.toolkit.clients.FakeClientVersionRepository
import rpcnode.toolkit.clients.FakeGitHubReleaseClient
import rpcnode.toolkit.clients.FakeGitHubTokenProvider
import rpcnode.toolkit.clients.application.probeone.ProbeClientProgramUseCase
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog
import rpcnode.toolkit.clients.infrastructure.tracking.InMemoryClientPreviewStore

class AddClientUseCaseTest
{
    private val catalog = YamlNetworkFactsRepository()

    private fun useCase(
        tokenProvider: FakeGitHubTokenProvider,
        versionRepository: FakeClientVersionRepository = FakeClientVersionRepository(),
    ) = AddClientUseCase(
        catalog = catalog,
        versionRepository = versionRepository,
        programCatalog = FakeClientProgramCatalog(),
        probeOne = ProbeClientProgramUseCase(versionRepository, FakeGitHubReleaseClient(), InMemoryClientPreviewStore()),
        tokenProvider = tokenProvider,
        backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        concurrency = Semaphore(2),
    )

    @Test
    fun unknown_network_is_rejected() = runTest {
        val result = useCase(FakeGitHubTokenProvider(null))("does-not-exist", "mainnet")
        assertIs<AddClientResult.UnknownNetwork>(result)
    }

    @Test
    fun unknown_env_is_rejected() = runTest {
        val result = useCase(FakeGitHubTokenProvider(null))("bitcoin", "not-a-real-env")
        assertIs<AddClientResult.UnknownEnv>(result)
    }

    @Test
    fun without_a_token_the_probe_is_not_queued() = runTest {
        val result = useCase(FakeGitHubTokenProvider(null))("bitcoin", "mainnet")
        val ok = assertIs<AddClientResult.Ok>(result)
        assertEquals(NetworkId.BITCOIN, ok.network)
        assertEquals(EnvId.MAINNET, ok.env)
        assertEquals(false, ok.probeQueued)
    }

    @Test
    fun with_a_token_the_probe_is_queued_and_any_purge_flag_is_cleared() = runTest {
        val repo = FakeClientVersionRepository()
        repo.markPurged(NetworkId.BITCOIN)

        val result = useCase(FakeGitHubTokenProvider("token"), repo)("bitcoin", "mainnet")

        val ok = assertIs<AddClientResult.Ok>(result)
        assertEquals(true, ok.probeQueued)
        assertEquals(false, repo.isPurged(NetworkId.BITCOIN))
    }

    @Test
    fun pin_only_ton_writes_the_pin_without_a_github_token() = runTest {
        val repo = FakeClientVersionRepository()
        val result = AddClientUseCase(
            catalog = catalog,
            versionRepository = repo,
            programCatalog = YamlClientProgramCatalog(),
            probeOne = ProbeClientProgramUseCase(repo, FakeGitHubReleaseClient(), InMemoryClientPreviewStore()),
            tokenProvider = FakeGitHubTokenProvider(null),
            backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            concurrency = Semaphore(2),
            clock = { "2026-09-03T00:00:00Z" },
        )("ton", "mainnet")

        val ok = assertIs<AddClientResult.Ok>(result)
        assertEquals(NetworkId.TON, ok.network)
        assertEquals(false, ok.probeQueued)
        val pin = repo.find(NetworkId.TON, EnvId.MAINNET, "validator-engine")
        assertNotNull(pin)
        assertEquals("2.18.0", pin.currentVersion)
        assertTrue(pin.skipReason.contains("pin-only", ignoreCase = true))
    }
}
