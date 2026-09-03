package rpcnode.toolkit.networks.application.snapshot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.networks.domain.model.SnapshotArchive

class ResolveSnapshotUseCaseTest
{
    @Test
    fun resolves_one_env_archive_for_a_network_in_the_map() = runTest {
        val tron = SnapshotResolver { env, _ ->
            if (env == EnvId.MAINNET)
            {
                SnapshotArchive(url = "https://mirror.example/latest.tgz", streamUnpack = true, sizeBytes = 100)
            }
            else
            {
                null
            }
        }
        val useCase = useCase(mapOf(NetworkId.TRON to tron))

        val mainnet = assertIs<ResolveSnapshotResult.Resolved>(useCase("tron", "mainnet"))
        assertEquals("https://mirror.example/latest.tgz", mainnet.archive?.url)
        assertTrue(mainnet.archive!!.streamUnpack)
        assertEquals(100, mainnet.archive.sizeBytes)

        val nile = assertIs<ResolveSnapshotResult.Resolved>(useCase("tron", "nile"))
        assertNull(nile.archive)
    }

    @Test
    fun a_network_without_a_resolver_resolves_to_null() = runTest {
        val result = assertIs<ResolveSnapshotResult.Resolved>(useCase(emptyMap())("bitcoin", "mainnet"))
        assertNull(result.archive)
    }

    @Test
    fun unknown_network_is_rejected() = runTest {
        assertIs<ResolveSnapshotResult.UnknownNetwork>(useCase(emptyMap())("does-not-exist", "mainnet"))
    }

    @Test
    fun env_that_the_chain_does_not_ship_is_rejected() = runTest {
        assertIs<ResolveSnapshotResult.UnknownEnv>(useCase(emptyMap())("bitcoin", "nile"))
    }

    private fun useCase(resolvers: Map<NetworkId, SnapshotResolver>) = ResolveSnapshotUseCase(
        catalog = YamlNetworkFactsRepository(),
        snapshotResolvers = resolvers,
    )
}
