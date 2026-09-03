package rpcnode.toolkit.settings.application.get

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.settings.FakeSettingsStore
import rpcnode.toolkit.settings.domain.model.InstallOrigin
import rpcnode.toolkit.settings.domain.repository.SettingsStore

class GetSettingsUseCaseTest
{
    @Test
    fun empty_store_is_not_configured() = runTest {
        val view = useCase().invoke("http://127.0.0.1:8093")
        assertFalse(view.configured)
        assertEquals("", view.channel.installOrigin)
        assertEquals("http://127.0.0.1:8093", view.presets.panel)
        assertEquals(InstallOrigin.LOCAL, view.presets.local)
        assertEquals(InstallOrigin.PROD, view.presets.prod)
        assertTrue(view.links.any { it.id == "healthz" })
        assertFalse(view.github.set)
    }

    @Test
    fun install_script_is_this_server_not_cdn() = runTest {
        val store = FakeSettingsStore()
        store.setInstallOrigin(okOrigin(InstallOrigin.PROD))
        val view = useCase(store = store).invoke("http://127.0.0.1:8093")
        assertEquals(
            "curl -fsSL -o rpcnode-agent.jar ${InstallOrigin.PROD}/install/binaries/rpcnode-agent.jar && sudo java -jar rpcnode-agent.jar install",
            view.curl,
        )
        assertEquals(view.curl, view.scripts.install)
        assertEquals(
            "curl -fsSL -o rpcnode-agent.jar ${InstallOrigin.PROD}/install/binaries/rpcnode-agent.jar && sudo java -jar rpcnode-agent.jar update",
            view.scripts.update,
        )
        assertEquals("sudo java -jar /opt/rpcnode/lib/rpcnode-agent.jar uninstall", view.scripts.uninstall)
        assertEquals("${InstallOrigin.PROD}/install/binaries/rpcnode-agent.jar", view.channel.agentDownloadUrl)
    }

    @Test
    fun env_origin_wins_over_store() = runTest {
        val store = FakeSettingsStore()
        store.setInstallOrigin(okOrigin(InstallOrigin.PROD))
        val view = useCase(store = store, envOrigin = InstallOrigin.LOCAL)
            .invoke("http://panel.example")
        assertEquals(InstallOrigin.LOCAL, view.channel.installOrigin)
        assertTrue(view.configured)
        assertTrue(view.links.any { it.id == "cdn_agent" && it.url.endsWith("/install/binaries/rpcnode-agent.jar") })
    }
}

internal fun settingsGetUseCase(
    store: SettingsStore = FakeSettingsStore(),
    envOrigin: String? = null,
    probe: UrlProbe = UrlProbe { false },
    files: InstallFiles = InstallFiles { false },
) = GetSettingsUseCase(
    store = store,
    probe = probe,
    installFiles = files,
    envOrigin = envOrigin,
    envSnapshotCdnOrigin = null,
    panelVersion = "test",
    installStamp = InstallStampReader { null },
)

private fun useCase(
    store: SettingsStore = FakeSettingsStore(),
    envOrigin: String? = null,
) = settingsGetUseCase(store, envOrigin)

private fun okOrigin(value: String): InstallOrigin =
    (InstallOrigin.parse(value) as InstallOrigin.Parse.Ok).origin
