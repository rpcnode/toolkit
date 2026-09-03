package rpcnode.toolkit.servers.application.register

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.application.probe.EnrollHostAgent
import rpcnode.toolkit.servers.application.probe.EnrollHostAgentResult
import rpcnode.toolkit.servers.application.probe.HostAgentClient
import rpcnode.toolkit.servers.application.probe.HostAgentIdentity
import rpcnode.toolkit.servers.domain.model.ServerId

class RegisterServerUseCaseTest
{
    private val clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneOffset.UTC)
    private val id = ServerId.parse("srv-1")!!

    private val identity = HostAgentClient { _, _ ->
        HostAgentIdentity(version = "0.1.1", os = "linux", arch = "amd64", osPretty = "linux/amd64")
    }

    @Test
    fun enrolls_the_agent_then_inserts() = runTest {
        val repo = FakeServerRepository()
        var enrolledUrl = ""
        var enrolledPanel = ""
        var enrolledId = ""
        val useCase = RegisterServerUseCase(
            servers = repo,
            enroll = EnrollHostAgent { url, token, panel, serverId ->
                enrolledUrl = url
                enrolledPanel = panel
                enrolledId = serverId
                assertEquals("from-agent-sh", token)
                EnrollHostAgentResult.Ok
            },
            identity = identity,
            clock = clock,
            newId = { id },
        )
        val created = assertIs<RegisterServerResult.Created>(
            useCase(
                agentUrlRaw = "http://10.0.0.5:48990/",
                agentKey = "from-agent-sh",
                name = "box",
                panelUrlRaw = "http://10.0.0.2:8093/",
            ),
        )
        assertEquals("http://10.0.0.5:48990", enrolledUrl)
        assertEquals("http://10.0.0.2:8093", enrolledPanel)
        assertEquals("srv-1", enrolledId)
        assertEquals("box", created.server.name)
        assertEquals("0.1.1", created.server.agentVersion)
        assertEquals("linux", created.server.os)
        assertEquals("box", repo.find(id)!!.name)
        assertEquals("0.1.1", repo.find(id)!!.agentVersion)
    }

    @Test
    fun enroll_failure_does_not_insert() = runTest {
        val repo = FakeServerRepository()
        val useCase = RegisterServerUseCase(
            servers = repo,
            enroll = EnrollHostAgent { _, _, _, _ -> EnrollHostAgentResult.Failed("HTTP 404") },
            identity = identity,
            clock = clock,
            newId = { id },
        )
        val failed = assertIs<RegisterServerResult.EnrollFailed>(
            useCase(
                agentUrlRaw = "http://10.0.0.5:48990",
                agentKey = "tok",
                panelUrlRaw = "http://10.0.0.2:8093",
            ),
        )
        assertEquals("HTTP 404", failed.detail)
        assertNull(repo.find(id))
    }

    @Test
    fun missing_key_or_panel_is_rejected() = runTest {
        val useCase = RegisterServerUseCase(
            FakeServerRepository(),
            EnrollHostAgent { _, _, _, _ -> error("should not enroll") },
            identity,
        )
        assertIs<RegisterServerResult.AgentKeyRequired>(
            useCase("http://10.0.0.5:48990", "", panelUrlRaw = "http://10.0.0.2:8093"),
        )
        assertIs<RegisterServerResult.PanelUrlRequired>(
            useCase("http://10.0.0.5:48990", "tok", panelUrlRaw = " "),
        )
    }

    @Test
    fun panel_unreachable_does_not_insert() = runTest {
        val repo = FakeServerRepository()
        val useCase = RegisterServerUseCase(
            servers = repo,
            enroll = EnrollHostAgent { _, _, _, _ -> EnrollHostAgentResult.PanelUnreachable },
            identity = identity,
            clock = clock,
            newId = { id },
        )
        val failed = assertIs<RegisterServerResult.PanelUnreachable>(
            useCase(
                agentUrlRaw = "http://10.0.0.5:48990",
                agentKey = "tok",
                panelUrlRaw = "http://10.0.0.2:8093",
            ),
        )
        assertEquals("http://10.0.0.2:8093", failed.panelUrl)
        assertNull(repo.find(id))
    }
}
