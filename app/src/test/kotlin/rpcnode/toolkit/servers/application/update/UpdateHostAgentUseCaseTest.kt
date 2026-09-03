package rpcnode.toolkit.servers.application.update

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.application.probe.HostAgentUpdate
import rpcnode.toolkit.servers.application.probe.UpdateHostAgent
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

class UpdateHostAgentUseCaseTest
{
    private val clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneOffset.UTC)
    private val server = Server(
        id = ServerId.parse("srv-1")!!,
        name = "box",
        agentUrl = "http://10.0.0.5:48990",
        agentKey = "tok",
        agentVersion = "0.1.0",
        createdAt = "t",
        updatedAt = "t",
    )

    @Test
    fun writes_the_new_version_after_a_successful_update() = runTest {
        val repo = FakeServerRepository(listOf(server))
        val useCase = UpdateHostAgentUseCase(
            servers = repo,
            update = UpdateHostAgent { url, token, force ->
                assertEquals("http://10.0.0.5:48990", url)
                assertEquals("tok", token)
                assertEquals(false, force)
                HostAgentUpdate(
                    ok = true,
                    updated = true,
                    version = "0.1.1",
                    remoteVersion = "0.1.1",
                    message = "installed",
                    status = 200,
                )
            },
            clock = clock,
        )
        val ok = assertIs<UpdateHostAgentResult.Ok>(useCase("srv-1"))
        assertEquals(true, ok.updated)
        assertEquals("0.1.1", ok.version)
        assertEquals("0.1.1", repo.find(server.id)!!.agentVersion)
    }

    @Test
    fun unknown_server_is_not_found() = runTest {
        val useCase = UpdateHostAgentUseCase(
            FakeServerRepository(),
            UpdateHostAgent { _, _, _ -> error("should not call") },
        )
        assertIs<UpdateHostAgentResult.NotFound>(useCase("srv-1"))
        assertIs<UpdateHostAgentResult.MissingServer>(useCase(" "))
    }
}
