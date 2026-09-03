package rpcnode.toolkit.settings.application.get

import rpcnode.toolkit.settings.domain.model.Channel
import rpcnode.toolkit.settings.domain.model.GitHubToken

data class SettingsView(
    val configured: Boolean,
    val panelVersion: String,
    val install: InstallStamp?,
    val channel: Channel,
    val snapshotCdn: SnapshotCdnView,
    val github: GitHubTokenView,
    val curl: String,
    val scripts: ScriptBundle,
    val panelScripts: ScriptBundle,
    val presets: OriginPresets,
    val links: List<ServiceLink>,
    val note: String,
    val warning: String? = null,
)

data class SnapshotCdnView(
    val origin: String,
    /** true when origin is set and probe reached `/` or `/snapshots/`. */
    val ok: Boolean,
)

data class GitHubTokenView(
    val set: Boolean,
    val decryptOk: Boolean,
    val masked: String,
)
{
    companion object
    {
        val ABSENT = GitHubTokenView(set = false, decryptOk = false, masked = "")
        val CORRUPT = GitHubTokenView(set = true, decryptOk = false, masked = "")

        fun present(token: GitHubToken) = GitHubTokenView(
            set = true,
            decryptOk = true,
            masked = token.masked,
        )
    }
}

data class ScriptBundle(
    val install: String,
    val update: String,
    val uninstall: String,
)

data class OriginPresets(
    val panel: String,
    val local: String,
    val prod: String,
)

data class ServiceLink(
    val id: String,
    val label: String,
    val url: String,
    val ok: Boolean,
)
