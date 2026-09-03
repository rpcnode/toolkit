package rpcnode.toolkit.clients.application.delete

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.FakeClientVersionRepository
import rpcnode.toolkit.clients.domain.model.ClientVersionPin

class DeleteClientUseCaseTest
{
    @Test
    fun deleting_one_env_wipes_only_that_dir_and_keeps_siblings() = runTest {
        val dest = Files.createTempDirectory("clients-dest")
        Files.createDirectories(dest.resolve("bitcoin/mainnet"))
        Files.writeString(dest.resolve("bitcoin/mainnet/VERSION"), "29.4\n")
        Files.createDirectories(dest.resolve("bitcoin/testnet4"))
        Files.writeString(dest.resolve("bitcoin/testnet4/VERSION"), "29.4\n")

        val repo = FakeClientVersionRepository(
            seed = listOf(
                ClientVersionPin(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin", currentVersion = "29.4"),
                ClientVersionPin(NetworkId.BITCOIN, EnvId.TESTNET4, "bitcoin", currentVersion = "29.4"),
            ),
        )
        val useCase = DeleteClientUseCase(repo, dest)

        val result = useCase("bitcoin", "mainnet")

        val ok = assertIs<DeleteClientResult.Ok>(result)
        assertFalse(ok.purged)
        assertFalse(Files.exists(dest.resolve("bitcoin/mainnet")))
        assertTrue(Files.exists(dest.resolve("bitcoin/testnet4")))
        assertNull(repo.find(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin"))
        assertEquals("29.4", repo.find(NetworkId.BITCOIN, EnvId.TESTNET4, "bitcoin")?.currentVersion)
        assertFalse(repo.isPurged(NetworkId.BITCOIN))
    }

    @Test
    fun deleting_a_whole_network_wipes_every_env_and_marks_it_purged() = runTest {
        val dest = Files.createTempDirectory("clients-dest")
        Files.createDirectories(dest.resolve("bitcoin/mainnet"))

        val repo = FakeClientVersionRepository(
            seed = listOf(ClientVersionPin(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin", currentVersion = "29.4")),
        )
        val useCase = DeleteClientUseCase(repo, dest)

        val result = useCase("bitcoin", null)

        val ok = assertIs<DeleteClientResult.Ok>(result)
        assertTrue(ok.purged)
        assertFalse(Files.exists(dest.resolve("bitcoin")))
        assertTrue(repo.list().isEmpty())
        assertTrue(repo.isPurged(NetworkId.BITCOIN))
    }

    @Test
    fun a_bad_network_segment_fails_cleanly() = runTest {
        val dest = Files.createTempDirectory("clients-dest")
        val result = DeleteClientUseCase(FakeClientVersionRepository(), dest)("../etc", null)
        assertIs<DeleteClientResult.Failed>(result)
    }
}
