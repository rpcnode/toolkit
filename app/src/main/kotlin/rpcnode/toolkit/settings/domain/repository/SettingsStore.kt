package rpcnode.toolkit.settings.domain.repository

import rpcnode.toolkit.settings.domain.model.GitHubToken
import rpcnode.toolkit.settings.domain.model.InstallOrigin
import rpcnode.toolkit.settings.domain.model.SnapshotCdnOrigin
import rpcnode.toolkit.settings.domain.model.StoredGitHubToken

interface SettingsStore
{
    suspend fun installOrigin(): InstallOrigin?
    suspend fun setInstallOrigin(origin: InstallOrigin)
    suspend fun snapshotCdnOrigin(): SnapshotCdnOrigin?
    suspend fun setSnapshotCdnOrigin(origin: SnapshotCdnOrigin)
    suspend fun clearSnapshotCdnOrigin()
    suspend fun githubToken(): StoredGitHubToken
    suspend fun setGithubToken(token: GitHubToken)
    suspend fun clearGithubToken()
    suspend fun setupStage(): String?
    suspend fun setSetupStage(stage: String)
}
