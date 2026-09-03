package rpcnode.toolkit.settings

import rpcnode.toolkit.settings.domain.model.GitHubToken
import rpcnode.toolkit.settings.domain.model.InstallOrigin
import rpcnode.toolkit.settings.domain.model.SnapshotCdnOrigin
import rpcnode.toolkit.settings.domain.model.StoredGitHubToken
import rpcnode.toolkit.settings.domain.repository.SettingsStore

internal class FakeSettingsStore : SettingsStore
{
    private var origin: InstallOrigin? = null
    private var snapshotCdn: SnapshotCdnOrigin? = null
    private var token: StoredGitHubToken = StoredGitHubToken.Absent

    override suspend fun installOrigin(): InstallOrigin? = origin

    override suspend fun setInstallOrigin(origin: InstallOrigin)
    {
        this.origin = origin
    }

    override suspend fun snapshotCdnOrigin(): SnapshotCdnOrigin? = snapshotCdn

    override suspend fun setSnapshotCdnOrigin(origin: SnapshotCdnOrigin)
    {
        this.snapshotCdn = origin
    }

    override suspend fun clearSnapshotCdnOrigin()
    {
        this.snapshotCdn = null
    }

    override suspend fun githubToken(): StoredGitHubToken = token

    override suspend fun setGithubToken(token: GitHubToken)
    {
        this.token = StoredGitHubToken.Present(token)
    }

    override suspend fun clearGithubToken()
    {
        this.token = StoredGitHubToken.Absent
    }
}
