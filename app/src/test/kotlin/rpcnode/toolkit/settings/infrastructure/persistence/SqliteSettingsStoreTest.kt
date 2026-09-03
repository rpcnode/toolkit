package rpcnode.toolkit.settings.infrastructure.persistence

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.settings.domain.model.GitHubToken
import rpcnode.toolkit.settings.domain.model.InstallOrigin
import rpcnode.toolkit.settings.domain.model.SnapshotCdnOrigin
import rpcnode.toolkit.settings.domain.model.StoredGitHubToken
import rpcnode.toolkit.shared.infrastructure.persistence.ToolkitDatabase

class SqliteSettingsStoreTest
{
    @Test
    fun origin_and_token_roundtrip() = runTest {
        val dir = Files.createTempDirectory("panel-settings")
        val secrets = AesGcmSecretBox(dir.resolve("panel.notify.key"))
        val store = SqliteSettingsStore(
            db = ToolkitDatabase(dir.resolve("toolkit.db")),
            githubTokenFile = dir.resolve("github-token"),
            secrets = secrets,
        )
        val origin = (InstallOrigin.parse(InstallOrigin.LOCAL) as InstallOrigin.Parse.Ok).origin
        store.setInstallOrigin(origin)
        assertEquals(InstallOrigin.LOCAL, store.installOrigin()?.value)

        val cdn = (SnapshotCdnOrigin.parse("http://127.0.0.1:8095") as SnapshotCdnOrigin.Parse.Ok).origin
        store.setSnapshotCdnOrigin(cdn)
        assertEquals("http://127.0.0.1:8095", store.snapshotCdnOrigin()?.value)
        store.clearSnapshotCdnOrigin()
        assertEquals(null, store.snapshotCdnOrigin())

        val token = GitHubToken.parse("ghp_abcdefghijklmnopcdef")!!
        store.setGithubToken(token)
        val present = assertIs<StoredGitHubToken.Present>(store.githubToken())
        assertEquals(token.value, present.token.value)
        assertEquals(token.value + "\n", Files.readString(dir.resolve("github-token")))

        store.clearGithubToken()
        assertEquals(StoredGitHubToken.Absent, store.githubToken())
        assertFalse(Files.exists(dir.resolve("github-token")))
    }

    @Test
    fun corrupt_ciphertext_is_flagged() = runTest {
        val dir = Files.createTempDirectory("panel-settings-bad")
        val db = ToolkitDatabase(dir.resolve("toolkit.db"))
        db.setSetting("github_token_enc", "not-valid-gcm")
        val store = SqliteSettingsStore(
            db = db,
            githubTokenFile = dir.resolve("github-token"),
            secrets = AesGcmSecretBox(dir.resolve("panel.notify.key")),
        )
        assertEquals(StoredGitHubToken.Corrupt, store.githubToken())
    }
}
