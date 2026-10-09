package rpcnode.toolkit.servers.application.remove

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.application.probe.UnenrollHostAgent
import rpcnode.toolkit.servers.domain.model.SERVER_REMOVE_STATUS_REMOVING
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

@OptIn(ExperimentalCoroutinesApi::class)
class RemoveServerUseCaseTest
{
    private val clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneOffset.UTC)
    private val server = Server(
        id = ServerId.parse("srv-1")!!,
        name = "box",
        agentUrl = "http://10.0.0.5:48990",
        agentKey = "tok",
        createdAt = "t",
        updatedAt = "t",
    )

    @Test
    fun queues_unenroll_then_soft_deletes() = runTest {
        val repo = FakeServerRepository(listOf(server))
        var unenrolledUrl = ""
        val finish = FinishRemoveServerUseCase(
            servers = repo,
            unenroll = UnenrollHostAgent { url, token ->
                unenrolledUrl = url
                assertEquals("tok", token)
                true
            },
            clock = clock,
        )
        val result = RemoveServerUseCase(
            servers = repo,
            nodes = FakeNodeRepository(),
            finish = finish,
            backgroundScope = this,
            clock = clock,
        )("srv-1")
        assertIs<RemoveServerResult.Queued>(result)
        advanceUntilIdle()
        assertEquals("http://10.0.0.5:48990", unenrolledUrl)
        assertTrue(repo.find(server.id)!!.isDeleted())
        assertEquals(0, repo.list().size)
    }

    @Test
    fun unenroll_failure_still_soft_deletes() = runTest {
        val repo = FakeServerRepository(listOf(server))
        val finish = FinishRemoveServerUseCase(
            servers = repo,
            unenroll = UnenrollHostAgent { _, _ -> false },
            clock = clock,
        )
        RemoveServerUseCase(
            servers = repo,
            nodes = FakeNodeRepository(),
            finish = finish,
            backgroundScope = this,
            clock = clock,
        )("srv-1")
        advanceUntilIdle()
        assertTrue(repo.find(server.id)!!.isDeleted())
    }

    @Test
    fun nodes_block_remove() = runTest {
        val repo = FakeServerRepository(listOf(server))
        val nodes = FakeNodeRepository(
            listOf(
                Node(
                    id = NodeId.parse("n1")!!,
                    serverId = server.id,
                    name = "tron mainnet",
                    network = NetworkId.TRON,
                    env = EnvId.MAINNET,
                    createdAt = "t",
                    updatedAt = "t",
                ),
            ),
        )
        val result = RemoveServerUseCase(
            servers = repo,
            nodes = nodes,
            finish = FinishRemoveServerUseCase(repo, UnenrollHostAgent { _, _ -> error("should not unenroll") }),
            backgroundScope = this,
            clock = clock,
        )("srv-1")
        val blocked = assertIs<RemoveServerResult.HasNodes>(result)
        assertEquals(1, blocked.count)
        assertEquals(false, repo.find(server.id)!!.isDeleted())
    }

    @Test
    fun missing_id_is_not_found() = runTest {
        val repo = FakeServerRepository(listOf(server))
        val result = RemoveServerUseCase(
            servers = repo,
            nodes = FakeNodeRepository(),
            finish = FinishRemoveServerUseCase(repo, UnenrollHostAgent { _, _ -> true }),
            backgroundScope = this,
        )("missing")
        assertIs<RemoveServerResult.NotFound>(result)
    }

    @Test
    fun already_removing_is_resumed() = runTest {
        val repo = FakeServerRepository(listOf(server.copy(removeStatus = SERVER_REMOVE_STATUS_REMOVING)))
        val result = RemoveServerUseCase(
            servers = repo,
            nodes = FakeNodeRepository(),
            finish = FinishRemoveServerUseCase(
                repo,
                UnenrollHostAgent { _, _ -> true },
                clock,
            ),
            backgroundScope = this,
            clock = clock,
        )("srv-1")
        assertIs<RemoveServerResult.AlreadyQueued>(result)
        advanceUntilIdle()
        assertTrue(repo.find(server.id)!!.isDeleted())
    }
}
