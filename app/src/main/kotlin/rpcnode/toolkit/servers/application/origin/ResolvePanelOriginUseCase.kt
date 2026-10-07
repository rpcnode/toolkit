package rpcnode.toolkit.servers.application.origin

import java.net.URI
import rpcnode.toolkit.settings.domain.model.InstallOrigin
import rpcnode.toolkit.settings.domain.repository.SettingsStore

/** Public panel origin the host agent can reach (ingest URL base). */
class ResolvePanelOriginUseCase(
    private val store: SettingsStore,
    private val envOrigin: String? = null,
    private val panelPort: Int = 8094,
)
{
    suspend operator fun invoke(explicit: String, requestOrigin: String): String
    {
        val stored = store.installOrigin()?.value.orEmpty()
        return listOf(envOrigin.orEmpty(), stored, explicit, requestOrigin)
            .map { normalize(it) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    private fun normalize(raw: String): String
    {
        val parsed = when (val got = InstallOrigin.parse(raw))
        {
            is InstallOrigin.Parse.Ok -> got.origin.value
            else -> raw.trim().trimEnd('/')
        }
        return rewriteDevProxy(parsed)
    }

    /** Admin Vite (:5173) is not the panel — the agent must hit rpcnode-server (:8094). */
    private fun rewriteDevProxy(url: String): String
    {
        val uri = try
        {
            URI(url)
        }
        catch (_: Exception)
        {
            return url
        }
        val host = uri.host?.lowercase() ?: return url
        val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if (loopback && uri.port == 5173)
        {
            return "http://127.0.0.1:$panelPort"
        }
        return url
    }
}
