package rpcnode.toolkit.clients.infrastructure.settings

import rpcnode.toolkit.clients.application.GitHubTokenProvider
import rpcnode.toolkit.settings.domain.model.StoredGitHubToken
import rpcnode.toolkit.settings.domain.repository.SettingsStore

/** Adapts the existing Settings [SettingsStore] port so `clients` doesn't depend on `panel.settings` domain types. */
class SettingsBackedGitHubTokenProvider(
    private val settingsStore: SettingsStore,
) : GitHubTokenProvider
{
    override suspend fun current(): String? = when (val stored = settingsStore.githubToken())
    {
        is StoredGitHubToken.Present -> stored.token.value
        StoredGitHubToken.Absent, StoredGitHubToken.Corrupt -> null
    }
}
