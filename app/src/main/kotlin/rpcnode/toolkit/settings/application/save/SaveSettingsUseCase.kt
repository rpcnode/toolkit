package rpcnode.toolkit.settings.application.save

import rpcnode.toolkit.settings.application.get.GetSettingsUseCase
import rpcnode.toolkit.settings.application.get.SettingsView
import rpcnode.toolkit.settings.application.get.UrlProbe
import rpcnode.toolkit.settings.application.get.snapshotCdnReachable
import rpcnode.toolkit.settings.domain.model.GitHubToken
import rpcnode.toolkit.settings.domain.model.InstallOrigin
import rpcnode.toolkit.settings.domain.model.SnapshotCdnOrigin
import rpcnode.toolkit.settings.domain.repository.SettingsStore

data class SaveSettingsCommand(
    val installOrigin: String? = null,
    /** null = leave unchanged; blank = clear (CDN off). */
    val snapshotCdnOrigin: String? = null,
    val githubToken: String? = null,
    val clearGithubToken: Boolean = false,
)

sealed interface SaveSettingsResult
{
    data class Ok(val view: SettingsView) : SaveSettingsResult
    data object OriginEmpty : SaveSettingsResult
    data object OriginInvalid : SaveSettingsResult
    data object SnapshotCdnOriginInvalid : SaveSettingsResult
    data object SnapshotCdnUnreachable : SaveSettingsResult
    data object GitHubTokenInvalid : SaveSettingsResult
    data class WriteFailed(val reason: String) : SaveSettingsResult
}

class SaveSettingsUseCase(
    private val store: SettingsStore,
    private val checker: GitHubTokenChecker,
    private val getSettings: GetSettingsUseCase,
    private val probe: UrlProbe,
)
{
    suspend operator fun invoke(cmd: SaveSettingsCommand, panelPublicOrigin: String): SaveSettingsResult
    {
        if (cmd.installOrigin != null)
        {
            when (val parsed = InstallOrigin.parse(cmd.installOrigin))
            {
                InstallOrigin.Parse.Empty -> return SaveSettingsResult.OriginEmpty
                InstallOrigin.Parse.Invalid -> return SaveSettingsResult.OriginInvalid
                is InstallOrigin.Parse.Ok ->
                {
                    try
                    {
                        store.setInstallOrigin(parsed.origin)
                    }
                    catch (e: Exception)
                    {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        return SaveSettingsResult.WriteFailed(e.message ?: "write_failed")
                    }
                }
            }
        }
        if (cmd.snapshotCdnOrigin != null)
        {
            when (val parsed = SnapshotCdnOrigin.parse(cmd.snapshotCdnOrigin))
            {
                SnapshotCdnOrigin.Parse.Empty ->
                {
                    try
                    {
                        store.clearSnapshotCdnOrigin()
                    }
                    catch (e: Exception)
                    {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        return SaveSettingsResult.WriteFailed(e.message ?: "write_failed")
                    }
                }
                SnapshotCdnOrigin.Parse.Invalid -> return SaveSettingsResult.SnapshotCdnOriginInvalid
                is SnapshotCdnOrigin.Parse.Ok ->
                {
                    if (!probe.snapshotCdnReachable(parsed.origin.value))
                    {
                        return SaveSettingsResult.SnapshotCdnUnreachable
                    }
                    try
                    {
                        store.setSnapshotCdnOrigin(parsed.origin)
                    }
                    catch (e: Exception)
                    {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        return SaveSettingsResult.WriteFailed(e.message ?: "write_failed")
                    }
                }
            }
        }
        if (cmd.clearGithubToken)
        {
            try
            {
                store.clearGithubToken()
            }
            catch (e: Exception)
            {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return SaveSettingsResult.WriteFailed(e.message ?: "write_failed")
            }
        }

        val rawToken = cmd.githubToken

        if (rawToken != null)
        {
            val token = GitHubToken.parse(rawToken)
            if (token != null)
            {
                when (val check = checker.check(token.value))
                {
                    GitHubTokenCheck.Rejected -> return SaveSettingsResult.GitHubTokenInvalid
                    is GitHubTokenCheck.Failed -> return SaveSettingsResult.WriteFailed(check.reason)
                    GitHubTokenCheck.Ok ->
                    {
                        try
                        {
                            store.setGithubToken(token)
                        }
                        catch (e: Exception)
                        {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            return SaveSettingsResult.WriteFailed(e.message ?: "write_failed")
                        }
                    }
                }
            }
        }
        return SaveSettingsResult.Ok(getSettings(panelPublicOrigin))
    }
}
