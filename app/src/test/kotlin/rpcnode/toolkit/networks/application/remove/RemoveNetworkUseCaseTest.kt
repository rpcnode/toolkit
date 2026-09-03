package rpcnode.toolkit.networks.application.remove

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.FakeNetworkRepository
import rpcnode.toolkit.networks.domain.model.Network
import rpcnode.toolkit.networks.domain.model.NetworkStatus

class RemoveNetworkUseCaseTest
{
    @Test
    fun removes_an_enabled_network() = runTest {
        val repo = FakeNetworkRepository(
            seed = listOf(Network(NetworkId.BITCOIN, NetworkStatus.READY, "now", "")),
        )
        val removed = RemoveNetworkUseCase(repo).invoke("bitcoin")
        assertEquals(NetworkId.BITCOIN, removed)
        assertEquals(emptyList(), repo.list())
    }

    @Test
    fun blank_id_is_rejected() = runTest {
        val repo = FakeNetworkRepository()
        assertNull(RemoveNetworkUseCase(repo).invoke(""))
    }
}
