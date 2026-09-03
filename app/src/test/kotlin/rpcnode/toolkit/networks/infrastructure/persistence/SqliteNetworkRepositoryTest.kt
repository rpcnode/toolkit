package rpcnode.toolkit.networks.infrastructure.persistence

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.model.NetworkStatus
import rpcnode.toolkit.shared.infrastructure.persistence.ToolkitDatabase

class SqliteNetworkRepositoryTest
{
    @Test
    fun upsert_then_list_then_delete_roundtrip() = runTest {
        val dir = Files.createTempDirectory("networks")
        val repo = SqliteNetworkRepository(ToolkitDatabase(dir.resolve("toolkit.db")))

        assertTrue(repo.list().isEmpty())

        repo.upsert(NetworkId.BITCOIN, NetworkStatus.READY, notes = "first")
        val listed = repo.list()
        assertEquals(1, listed.size)
        assertEquals(NetworkId.BITCOIN, listed.single().network)
        assertEquals(NetworkStatus.READY, listed.single().status)
        assertEquals("first", listed.single().notes)
        assertTrue(listed.single().addedAt.isNotEmpty())

        repo.upsert(NetworkId.BITCOIN, NetworkStatus.SKIPPED, notes = "changed my mind")
        val updated = repo.list()
        assertEquals(1, updated.size)
        assertEquals(NetworkStatus.SKIPPED, updated.single().status)
        assertEquals("changed my mind", updated.single().notes)

        repo.delete(NetworkId.BITCOIN)
        assertTrue(repo.list().isEmpty())
    }
}
