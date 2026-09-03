package rpcnode.toolkit.clients.infrastructure.persistence

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.shared.infrastructure.persistence.ToolkitDatabase

class SqliteClientVersionRepositoryTest
{
    private fun newRepo(): SqliteClientVersionRepository
    {
        val dir = Files.createTempDirectory("client-versions")
        return SqliteClientVersionRepository(ToolkitDatabase(dir.resolve("toolkit.db")))
    }

    @Test
    fun apply_probe_never_creates_a_row() = runTest {
        val repo = newRepo()
        repo.applyProbe(
            ClientVersionPin(
                network = NetworkId.BITCOIN,
                env = EnvId.MAINNET,
                program = "bitcoin",
                latestVersion = "29.4",
            ),
        )
        assertTrue(repo.list().isEmpty())
        assertNull(repo.find(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin"))
    }

    @Test
    fun apply_synced_upserts_a_composite_key_row_then_apply_probe_updates_it() = runTest {
        val repo = newRepo()
        repo.applySynced(
            ClientVersionPin(
                network = NetworkId.BITCOIN,
                env = EnvId.MAINNET,
                program = "bitcoin",
                currentVersion = "29.4",
                currentTag = "v29.4",
                latestVersion = "29.4",
                latestTag = "v29.4",
                source = "bitcoin/bitcoin",
            ),
        )
        val synced = repo.find(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin")
        assertEquals("29.4", synced?.currentVersion)

        repo.applyProbe(synced!!.copy(latestVersion = "29.5", latestTag = "v29.5"))
        val probed = repo.find(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin")
        assertEquals("29.4", probed?.currentVersion)
        assertEquals("29.5", probed?.latestVersion)

        assertEquals(1, repo.list().size)
    }

    @Test
    fun composite_key_keeps_multiple_programs_on_the_same_network_env_distinct() = runTest {
        val repo = newRepo()
        repo.applySynced(ClientVersionPin(NetworkId.TRON, EnvId.MAINNET, "geth", currentVersion = "1.0"))
        repo.applySynced(ClientVersionPin(NetworkId.TRON, EnvId.MAINNET, "lighthouse", currentVersion = "2.0"))
        assertEquals(2, repo.list().size)
        assertEquals("1.0", repo.find(NetworkId.TRON, EnvId.MAINNET, "geth")?.currentVersion)
        assertEquals("2.0", repo.find(NetworkId.TRON, EnvId.MAINNET, "lighthouse")?.currentVersion)
    }

    @Test
    fun delete_env_only_removes_that_env() = runTest {
        val repo = newRepo()
        repo.applySynced(ClientVersionPin(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin", currentVersion = "1.0"))
        repo.applySynced(ClientVersionPin(NetworkId.BITCOIN, EnvId.TESTNET4, "bitcoin", currentVersion = "1.0"))

        repo.deleteEnv(NetworkId.BITCOIN, EnvId.MAINNET)

        assertEquals(1, repo.list().size)
        assertNull(repo.find(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin"))
        assertEquals("1.0", repo.find(NetworkId.BITCOIN, EnvId.TESTNET4, "bitcoin")?.currentVersion)
    }

    @Test
    fun delete_network_removes_every_env() = runTest {
        val repo = newRepo()
        repo.applySynced(ClientVersionPin(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin", currentVersion = "1.0"))
        repo.applySynced(ClientVersionPin(NetworkId.BITCOIN, EnvId.TESTNET4, "bitcoin", currentVersion = "1.0"))

        repo.deleteNetwork(NetworkId.BITCOIN)

        assertTrue(repo.list().isEmpty())
    }

    @Test
    fun purge_roundtrip() = runTest {
        val repo = newRepo()
        assertFalse(repo.isPurged(NetworkId.BITCOIN))
        repo.markPurged(NetworkId.BITCOIN)
        assertTrue(repo.isPurged(NetworkId.BITCOIN))
        repo.clearPurged(NetworkId.BITCOIN)
        assertFalse(repo.isPurged(NetworkId.BITCOIN))
    }
}
