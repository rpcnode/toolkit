package rpcnode.toolkit.settings.application.get

import java.net.URI
import rpcnode.toolkit.settings.domain.model.Channel
import rpcnode.toolkit.settings.domain.model.InstallOrigin
import rpcnode.toolkit.settings.domain.model.SnapshotCdnOrigin
import rpcnode.toolkit.settings.domain.model.StoredGitHubToken
import rpcnode.toolkit.settings.domain.repository.SettingsStore

class GetSettingsUseCase(
    private val store: SettingsStore,
    private val probe: UrlProbe,
    private val installFiles: InstallFiles,
    private val envOrigin: String?,
    private val envSnapshotCdnOrigin: String?,
    private val panelVersion: String,
    private val installStamp: InstallStampReader,
)
{
    suspend operator fun invoke(panelPublicOrigin: String, warning: String? = null): SettingsView
    {
        val channel = resolveChannel()
        val snapshotCdn = resolveSnapshotCdn()
        val panel = panelPublicOrigin.trimEnd('/')
        val jarUrl = channel.agentDownloadUrl.ifBlank { "$panel/install/binaries/rpcnode-agent.jar" }
        val installCmd = agentJarInstallCommand(jarUrl)
        val updateCmd = agentJarInstallCommand(jarUrl, action = "update")
        val uninstallCmd = "sudo java -jar /opt/rpcnode/lib/rpcnode-agent.jar uninstall"
        val github = when (val tok = store.githubToken())
        {
            StoredGitHubToken.Absent -> GitHubTokenView.ABSENT
            StoredGitHubToken.Corrupt -> GitHubTokenView.CORRUPT
            is StoredGitHubToken.Present -> GitHubTokenView.present(tok.token)
        }
        return SettingsView(
            configured = channel.configured,
            panelVersion = panelVersion,
            install = installStamp.read(),
            channel = channel,
            snapshotCdn = snapshotCdn,
            github = github,
            curl = installCmd,
            scripts = ScriptBundle(
                install = installCmd,
                update = updateCmd,
                uninstall = uninstallCmd,
            ),
            panelScripts = ScriptBundle(
                install = "sudo ./scripts/install-rpcnode-server.sh",
                update = "sudo ./scripts/install-rpcnode-server.sh --update",
                uninstall = "sudo ./scripts/install-rpcnode-server.sh --uninstall",
            ),
            presets = OriginPresets(
                panel = panel,
                local = InstallOrigin.LOCAL,
                prod = InstallOrigin.PROD,
            ),
            links = links(panel, channel),
            note = "This panel serves /install/ from public/install. Origin is stored in toolkit.db.",
            warning = warning,
        )
    }

    private suspend fun resolveChannel(): Channel
    {
        val fromEnv = envOrigin?.let { raw ->
            when (val parsed = InstallOrigin.parse(raw))
            {
                is InstallOrigin.Parse.Ok -> parsed.origin.channel()
                else -> null
            }
        }
        if (fromEnv != null)
        {
            return fromEnv
        }
        return store.installOrigin()?.channel() ?: Channel.EMPTY
    }

    private suspend fun resolveSnapshotCdn(): SnapshotCdnView
    {
        val origin = resolveSnapshotCdnOrigin() ?: return SnapshotCdnView(origin = "", ok = false)
        return SnapshotCdnView(origin = origin.value, ok = probe.snapshotCdnReachable(origin.value))
    }

    private suspend fun resolveSnapshotCdnOrigin(): SnapshotCdnOrigin?
    {
        val fromEnv = envSnapshotCdnOrigin?.let { raw ->
            when (val parsed = SnapshotCdnOrigin.parse(raw))
            {
                is SnapshotCdnOrigin.Parse.Ok -> parsed.origin
                else -> null
            }
        }
        if (fromEnv != null)
        {
            return fromEnv
        }
        return store.snapshotCdnOrigin()
    }

    private suspend fun links(panel: String, channel: Channel): List<ServiceLink>
    {
        val out = mutableListOf(
            ServiceLink(id = "panel", label = "Panel", url = "$panel/", ok = true),
            ServiceLink(id = "healthz", label = "healthz", url = "$panel/healthz", ok = true),
        )
        if (installFiles.exists("binaries/sha256sums.txt"))
        {
            out += ServiceLink(
                id = "panel_bins",
                label = "binaries",
                url = "$panel/install/binaries/sha256sums.txt",
                ok = true,
            )
        }
        else if (installFiles.exists("binaries/rpcnode-agent.jar"))
        {
            out += ServiceLink(
                id = "panel_bins",
                label = "binaries",
                url = "$panel/install/binaries/rpcnode-agent.jar",
                ok = true,
            )
        }
        val cdn = channel.agentDownloadUrl
        if (cdn.isNotEmpty())
        {
            out += ServiceLink(
                id = "cdn_agent",
                label = "Install agent jar",
                url = cdn,
                ok = probe.reachable(cdn) || loopbackInstallOnDisk(cdn),
            )
        }
        return out
    }

    private fun loopbackInstallOnDisk(raw: String): Boolean
    {
        val uri = try
        {
            URI(raw)
        }
        catch (_: Exception)
        {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        if (host !in LOOPBACK_HOSTS)
        {
            return false
        }
        val path = uri.path ?: return false
        if (!path.startsWith("/install/"))
        {
            return false
        }
        val rel = path.removePrefix("/install/")
        if (rel.isEmpty() || ".." in rel)
        {
            return false
        }
        return installFiles.exists(rel)
    }

    private companion object {
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "host.docker.internal", "::1")
    }
}

/** Download the agent jar from the panel, then self-install (same idea as rpcnode-cdn.jar install). */
internal fun agentJarInstallCommand(jarUrl: String, action: String = "install"): String =
    "curl -fsSL -o rpcnode-agent.jar $jarUrl && sudo java -jar rpcnode-agent.jar $action"
