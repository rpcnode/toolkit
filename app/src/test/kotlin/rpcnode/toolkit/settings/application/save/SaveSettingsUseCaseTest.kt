package rpcnode.toolkit.settings.application.save

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.settings.FakeSettingsStore
import rpcnode.toolkit.settings.application.get.UrlProbe
import rpcnode.toolkit.settings.application.get.settingsGetUseCase
import rpcnode.toolkit.settings.domain.model.GitHubToken
import rpcnode.toolkit.settings.domain.model.InstallOrigin

private val alwaysReachable = UrlProbe { true }

class SaveSettingsUseCaseTest
{
    @Test
    fun saves_origin() = runTest {
        val store = FakeSettingsStore()
        val save = SaveSettingsUseCase(
            store = store,
            checker = GitHubTokenChecker { GitHubTokenCheck.Ok },
            getSettings = settingsGetUseCase(store),
            probe = alwaysReachable,
        )
        val result = save(
            SaveSettingsCommand(installOrigin = "https://toolkit.rpcnode.dev"),
            "http://127.0.0.1:8093",
        )
        val ok = assertIs<SaveSettingsResult.Ok>(result)
        assertEquals(InstallOrigin.PROD, ok.view.channel.installOrigin)
        assertEquals(InstallOrigin.PROD, store.installOrigin()?.value)
    }

    @Test
    fun rejects_invalid_origin() = runTest {
        val save = SaveSettingsUseCase(
            store = FakeSettingsStore(),
            checker = GitHubTokenChecker { GitHubTokenCheck.Ok },
            getSettings = settingsGetUseCase(),
            probe = alwaysReachable,
        )
        assertIs<SaveSettingsResult.OriginInvalid>(
            save(SaveSettingsCommand(installOrigin = "ftp://x"), "http://127.0.0.1:8093"),
        )
    }

    @Test
    fun saves_and_clears_snapshot_cdn_origin() = runTest {
        val store = FakeSettingsStore()
        val save = SaveSettingsUseCase(
            store = store,
            checker = GitHubTokenChecker { GitHubTokenCheck.Ok },
            getSettings = settingsGetUseCase(store),
            probe = alwaysReachable,
        )
        val saved = assertIs<SaveSettingsResult.Ok>(
            save(
                SaveSettingsCommand(snapshotCdnOrigin = "http://127.0.0.1:8095/"),
                "http://127.0.0.1:8093",
            ),
        )
        assertEquals("http://127.0.0.1:8095", saved.view.snapshotCdn.origin)
        assertEquals("http://127.0.0.1:8095", store.snapshotCdnOrigin()?.value)

        val cleared = assertIs<SaveSettingsResult.Ok>(
            save(SaveSettingsCommand(snapshotCdnOrigin = ""), "http://127.0.0.1:8093"),
        )
        assertEquals("", cleared.view.snapshotCdn.origin)
        assertNull(store.snapshotCdnOrigin())
    }

    @Test
    fun rejects_invalid_snapshot_cdn_origin() = runTest {
        val save = SaveSettingsUseCase(
            store = FakeSettingsStore(),
            checker = GitHubTokenChecker { GitHubTokenCheck.Ok },
            getSettings = settingsGetUseCase(),
            probe = alwaysReachable,
        )
        assertIs<SaveSettingsResult.SnapshotCdnOriginInvalid>(
            save(SaveSettingsCommand(snapshotCdnOrigin = "ftp://x"), "http://127.0.0.1:8093"),
        )
    }

    @Test
    fun github_rejected() = runTest {
        val save = SaveSettingsUseCase(
            store = FakeSettingsStore(),
            checker = GitHubTokenChecker { GitHubTokenCheck.Rejected },
            getSettings = settingsGetUseCase(),
            probe = alwaysReachable,
        )
        assertIs<SaveSettingsResult.GitHubTokenInvalid>(
            save(SaveSettingsCommand(githubToken = "ghp_nope"), "http://127.0.0.1:8093"),
        )
    }

    @Test
    fun github_saved_and_cleared() = runTest {
        val store = FakeSettingsStore()
        val get = settingsGetUseCase(store)
        val save = SaveSettingsUseCase(
            store = store,
            checker = GitHubTokenChecker { GitHubTokenCheck.Ok },
            getSettings = get,
            probe = alwaysReachable,
        )
        val saved = assertIs<SaveSettingsResult.Ok>(
            save(SaveSettingsCommand(githubToken = "ghp_abcdefghijklmnopcdef"), "http://127.0.0.1:8093"),
        )
        assertTrue(saved.view.github.set)
        assertTrue(saved.view.github.decryptOk)
        assertEquals("ghp_…cdef", saved.view.github.masked)

        val cleared = assertIs<SaveSettingsResult.Ok>(
            save(SaveSettingsCommand(clearGithubToken = true), "http://127.0.0.1:8093"),
        )
        assertFalse(cleared.view.github.set)
        assertNull(GitHubToken.parse(""))
    }
}
