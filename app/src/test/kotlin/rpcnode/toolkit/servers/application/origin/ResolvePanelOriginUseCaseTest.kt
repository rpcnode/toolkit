package rpcnode.toolkit.servers.application.origin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.settings.FakeSettingsStore

class ResolvePanelOriginUseCaseTest
{
    @Test
    fun rewrites_vite_loopback_to_panel_port() = runTest {
        val useCase = ResolvePanelOriginUseCase(FakeSettingsStore())
        assertEquals(
            "http://127.0.0.1:8093",
            useCase("http://localhost:5173", ""),
        )
        assertEquals(
            "http://127.0.0.1:8093",
            useCase("http://127.0.0.1:5173", ""),
        )
    }

    @Test
    fun keeps_a_real_panel_origin() = runTest {
        val useCase = ResolvePanelOriginUseCase(FakeSettingsStore())
        assertEquals("http://10.0.0.2:8093", useCase("http://10.0.0.2:8093/", ""))
    }
}
