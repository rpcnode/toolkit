package rpcnode.toolkit.agent.application.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class UpdateAgentUseCaseTest
{
    @Test
    fun already_on_channel_does_not_install() = runTest {
        var installed = 0
        val useCase = UpdateAgentUseCase(
            localVersion = "0.1.1",
            resolvePanelUrl = { "http://10.0.0.2:8093" },
            channel = AgentReleaseChannel { "0.1.1" },
            installer = AgentJarInstaller {
                installed += 1
                AgentInstallResult.Ok("/opt/rpcnode/lib/rpcnode-agent.jar")
            },
            restarter = AgentRestarter { },
        )
        val got = assertIs<UpdateAgentResult.UpToDate>(useCase(force = false))
        assertEquals("0.1.1", got.version)
        assertEquals(0, installed)
    }

    @Test
    fun outdated_installs_and_schedules_restart() = runTest {
        var restarted = 0
        val useCase = UpdateAgentUseCase(
            localVersion = "0.1.0",
            resolvePanelUrl = { "http://10.0.0.2:8093" },
            channel = AgentReleaseChannel { "0.1.1" },
            installer = AgentJarInstaller { AgentInstallResult.Ok("/opt/rpcnode/lib/rpcnode-agent.jar") },
            restarter = AgentRestarter { restarted += 1 },
        )
        val got = assertIs<UpdateAgentResult.Updated>(useCase())
        assertEquals("0.1.1", got.version)
        assertEquals("0.1.0", got.localVersion)
        assertEquals(1, restarted)
    }

    @Test
    fun missing_panel_is_channel_unavailable() = runTest {
        val useCase = UpdateAgentUseCase(
            localVersion = "0.1.0",
            resolvePanelUrl = { null },
            channel = AgentReleaseChannel { "0.1.1" },
            installer = AgentJarInstaller { error("should not install") },
            restarter = AgentRestarter { },
        )
        assertIs<UpdateAgentResult.ChannelUnavailable>(useCase())
    }
}
