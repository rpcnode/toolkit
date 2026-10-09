package rpcnode.toolkit.networks.application.list

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.FakeNetworkFactsRepository
import rpcnode.toolkit.networks.FakeNetworkRepository
import rpcnode.toolkit.networks.application.ClientFilesReadyChecker
import rpcnode.toolkit.networks.domain.model.Network
import rpcnode.toolkit.networks.domain.model.NetworkDiskRoleFacts
import rpcnode.toolkit.networks.domain.model.NetworkEnvFacts
import rpcnode.toolkit.networks.domain.model.NetworkFacts
import rpcnode.toolkit.networks.domain.model.NetworkStatus
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository

class ListNetworksUseCaseTest
{
    @Test
    fun without_all_is_empty_when_nothing_enabled() = runTest {
        val items = useCase()(all = false)
        assertTrue(items.isEmpty())
    }

    @Test
    fun without_all_returns_only_enabled_networks() = runTest {
        val repo = FakeNetworkRepository(
            seed = listOf(Network(NetworkId.BITCOIN, NetworkStatus.READY, "now", "")),
        )
        val items = useCase(networkRepo = repo)(all = false)
        assertEquals(1, items.size)
        val bitcoin = items.single()
        assertEquals(NetworkId.BITCOIN, bitcoin.id)
        assertTrue(bitcoin.enabled)
        assertEquals(NetworkStatus.READY, bitcoin.status)
    }

    @Test
    fun with_all_shows_the_whole_catalog_even_when_not_enabled() = runTest {
        val catalogIds = YamlNetworkFactsRepository().all().map { it.id }
        val items = useCase()(all = true)
        assertEquals(catalogIds.size, items.size)
        assertTrue(items.none { it.enabled })
        assertTrue(items.all { it.status == null })
    }

    @Test
    fun files_ready_is_reported_independently_of_enabled_status() = runTest {
        val items = useCase(filesReady = ClientFilesReadyChecker { _, _ -> true })(all = true)
        assertTrue(items.all { it.filesReady })
    }

    @Test
    fun facts_are_substituted_in_by_network_id() = runTest {
        val shipped = NetworkFacts(
            envs = listOf(NetworkEnvFacts(id = "mainnet", diskHintGiB = 1024.0, cpuCores = 4.0, memoryGiB = 16.0)),
            diskRoles = listOf(NetworkDiskRoleFacts(id = "blockchain", label = "Blockchain data", media = "ssd")),
            diskNotes = listOf("note"),
        )
        val facts = FakeNetworkFactsRepository(mapOf(NetworkId.BITCOIN to shipped))
        val items = useCase(facts = facts)(all = true)
        val bitcoin = items.single { it.id == NetworkId.BITCOIN }
        assertEquals(shipped, bitcoin.facts)
        val tron = items.single { it.id == NetworkId.TRON }
        assertNull(tron.facts)
    }

    @Test
    fun facts_are_null_when_this_install_ships_none_for_the_network() = runTest {
        val items = useCase()(all = true)
        assertTrue(items.all { it.facts == null })
    }

    @Test
    fun real_bitcoin_yaml_facts_are_loaded_and_substituted() = runTest {
        val items = useCase(facts = YamlNetworkFactsRepository())(all = true)
        val bitcoin = items.single { it.id == NetworkId.BITCOIN }
        val facts = bitcoin.facts
        assertNotNull(facts)
        assertEquals(4, facts.envs.size)
        val mainnet = facts.envs.single { it.id == "mainnet" }
        assertEquals(1024.0, mainnet.diskHintGiB)
        assertEquals(4.0, mainnet.cpuCores)
        assertEquals(16.0, mainnet.memoryGiB)
        assertEquals(2, facts.diskRoles.size)
    }

    @Test
    fun real_tron_yaml_facts_are_loaded_and_substituted() = runTest {
        val items = useCase(facts = YamlNetworkFactsRepository())(all = true)
        val tron = items.single { it.id == NetworkId.TRON }
        val facts = tron.facts
        assertNotNull(facts)
        assertEquals(3, facts.envs.size)
        val mainnet = facts.envs.single { it.id == "mainnet" }
        assertEquals(4096.0, mainnet.diskHintGiB)
        assertEquals(2900.0, mainnet.fullNodeGiB)
        assertEquals(3600.0, mainnet.archiveGiB)
        assertEquals("required", mainnet.snapshot)
        assertEquals(2, facts.diskRoles.size)
    }

    private fun useCase(
        networkRepo: FakeNetworkRepository = FakeNetworkRepository(),
        filesReady: ClientFilesReadyChecker = ClientFilesReadyChecker { _, _ -> false },
        facts: NetworkFactsRepository = FakeNetworkFactsRepository(),
    ) = ListNetworksUseCase(
        catalog = YamlNetworkFactsRepository(),
        networkRepo = networkRepo,
        filesReady = filesReady,
        facts = facts,
    )
}
