package rpcnode.toolkit.servers.application.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class ProbeHostAgentUseCaseTest
{
    @Test
    fun missing_url_or_token_is_rejected() = runTest {
        val useCase = ProbeHostAgentUseCase { _, _ -> error("should not call") }
        assertIs<ProbeHostAgentResult.MissingUrl>(useCase("", "tok"))
        assertIs<ProbeHostAgentResult.MissingToken>(useCase("http://127.0.0.1:38990", "  "))
    }

    @Test
    fun a_reachable_agent_returns_identity() = runTest {
        val useCase = ProbeHostAgentUseCase { url, token ->
            assertEquals("http://10.0.0.5:38990", url)
            assertEquals("from-agent-sh", token)
            HostAgentIdentity(version = "0.1.0", os = "linux", arch = "amd64", osPretty = "linux/amd64")
        }
        val ok = assertIs<ProbeHostAgentResult.Ok>(useCase("http://10.0.0.5:38990/", "from-agent-sh"))
        assertEquals("0.1.0", ok.identity.version)
        assertEquals("http://10.0.0.5:38990", ok.agentUrl)
    }

    @Test
    fun rejected_token_is_invalid_token() = runTest {
        val client = object : HostAgentClient
        {
            override suspend fun identity(agentUrl: String, token: String): HostAgentIdentity? = null

            override suspend fun lookup(agentUrl: String, token: String): HostAgentLookup =
                HostAgentLookup.Unauthorized(401)
        }
        val useCase = ProbeHostAgentUseCase(client)
        val bad = assertIs<ProbeHostAgentResult.InvalidToken>(useCase("http://10.0.0.5:48990", "wrong"))
        assertEquals("http://10.0.0.5:48990", bad.agentUrl)
    }
}
