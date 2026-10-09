package rpcnode.toolkit.servers.application.update

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.application.probe.EnrollHostAgent
import rpcnode.toolkit.servers.application.probe.EnrollHostAgentResult
import rpcnode.toolkit.servers.application.probe.HostAgentClient
import rpcnode.toolkit.servers.application.probe.HostAgentIdentity
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

class UpdateServerUseCaseTest
{
    private val clock = Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC)
    private val id = ServerId.parse("srv-1")!!

    private val existing = Server(
        id = id,
        name = "box",
        agentUrl = "http://10.0.0.5:48990",
        agentKey = "old-token",
        os = "linux",
        arch = "amd64",
        createdAt = "2026-08-01T00:00:00Z",
        updatedAt = "2026-08-01T00:00:00Z",
    )

    private val identity = HostAgentClient { _, _ ->
        HostAgentIdentity(version = "0.2.0", os = "linux", arch = "amd64", osPretty = "linux/amd64")
    }

    @Test
    fun updates_key_in_place_without_new_id() = runTest {
        val repo = FakeServerRepository(listOf(existing))
        var enrolledId = ""
        var enrolledToken = ""
        val useCase = UpdateServerUseCase(
            servers = repo,
            enroll = EnrollHostAgent { _, token, _, serverId ->
                enrolledToken = token
                enrolledId = serverId
                EnrollHostAgentResult.Ok
            },
            identity = identity,
            clock = clock,
        )
        val updated = assertIs<UpdateServerResult.Updated>(
            useCase(
                idRaw = "srv-1",
                agentUrlRaw = "http://10.0.0.5:48990/",
                agentKeyRaw = "new-token",
                name = "box",
                panelUrlRaw = "http://10.0.0.2:8093/",
            ),
        )
        assertEquals("srv-1", updated.server.id.value)
        assertEquals("srv-1", enrolledId)
        assertEquals("new-token", enrolledToken)
        assertEquals("new-token", updated.server.agentKey)
        assertEquals("0.2.0", updated.server.agentVersion)
        assertEquals(1, repo.list().size)
        assertEquals("new-token", repo.find(id)!!.agentKey)
    }

    @Test
    fun blank_key_keeps_existing() = runTest {
        val repo = FakeServerRepository(listOf(existing))
        var enrolledToken = ""
        val useCase = UpdateServerUseCase(
            servers = repo,
            enroll = EnrollHostAgent { _, token, _, _ ->
                enrolledToken = token
                EnrollHostAgentResult.Ok
            },
            identity = identity,
            clock = clock,
        )
        val updated = assertIs<UpdateServerResult.Updated>(
            useCase(
                idRaw = "srv-1",
                agentUrlRaw = "http://10.0.0.5:48990",
                agentKeyRaw = "",
                panelUrlRaw = "http://10.0.0.2:8093",
            ),
        )
        assertEquals("old-token", enrolledToken)
        assertEquals("old-token", updated.server.agentKey)
    }

    @Test
    fun missing_server_is_not_found() = runTest {
        val useCase = UpdateServerUseCase(
            servers = FakeServerRepository(),
            enroll = EnrollHostAgent { _, _, _, _ -> EnrollHostAgentResult.Ok },
            identity = identity,
            clock = clock,
        )
        assertIs<UpdateServerResult.NotFound>(
            useCase(
                idRaw = "missing",
                agentUrlRaw = "http://10.0.0.5:48990",
                agentKeyRaw = "tok",
                panelUrlRaw = "http://10.0.0.2:8093",
            ),
        )
    }

    @Test
    fun enroll_unauthorized_is_invalid_agent_key() = runTest {
        val repo = FakeServerRepository(listOf(existing))
        val useCase = UpdateServerUseCase(
            servers = repo,
            enroll = EnrollHostAgent { _, _, _, _ -> EnrollHostAgentResult.Unauthorized },
            identity = identity,
            clock = clock,
        )
        val failed = assertIs<UpdateServerResult.InvalidAgentKey>(
            useCase(
                idRaw = "srv-1",
                agentUrlRaw = "http://10.0.0.5:48990",
                agentKeyRaw = "wrong",
                panelUrlRaw = "http://10.0.0.2:8093",
            ),
        )
        assertEquals("http://10.0.0.5:48990", failed.agentUrl)
        assertEquals("old-token", repo.find(id)!!.agentKey)
    }
}
