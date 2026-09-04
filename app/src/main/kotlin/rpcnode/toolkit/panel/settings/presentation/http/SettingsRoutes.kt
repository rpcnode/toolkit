package rpcnode.toolkit.panel.settings.presentation.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rpcnode.toolkit.panel.auth.presentation.http.AuthErrorResponse
import rpcnode.toolkit.panel.auth.presentation.http.sessionToken
import rpcnode.toolkit.settings.application.get.InstallStamp
import rpcnode.toolkit.settings.application.get.ScriptBundle
import rpcnode.toolkit.settings.application.get.ServiceLink
import rpcnode.toolkit.settings.application.get.SettingsView
import rpcnode.toolkit.settings.application.save.SaveSettingsCommand
import rpcnode.toolkit.settings.application.save.SaveSettingsResult
import rpcnode.toolkit.settings.domain.model.GitHubToken
import rpcnode.toolkit.wiring.Toolkit

@Serializable
data class SettingsPutBody(
    @SerialName("install_origin") val installOrigin: String? = null,
    @SerialName("snapshot_cdn_origin") val snapshotCdnOrigin: String? = null,
    @SerialName("github_token") val githubToken: String? = null,
    @SerialName("clear_github_token") val clearGithubToken: Boolean = false,
)

@Serializable
data class SettingsResponse(
    val ok: Boolean = true,
    val configured: Boolean,
    @SerialName("panel_version") val panelVersion: String = "",
    val install: InstallStampResponse? = null,
    @SerialName("install_origin") val installOrigin: String = "",
    @SerialName("clients_base_url") val clientsBaseUrl: String = "",
    @SerialName("install_base_url") val installBaseUrl: String = "",
    @SerialName("agent_download_url") val agentDownloadUrl: String = "",
    @SerialName("snapshot_cdn_origin") val snapshotCdnOrigin: String = "",
    @SerialName("snapshot_cdn") val snapshotCdn: SnapshotCdnResponse = SnapshotCdnResponse(),
    @SerialName("github_token_set") val githubTokenSet: Boolean = false,
    @SerialName("github_token_decrypt_ok") val githubTokenDecryptOk: Boolean = false,
    @SerialName("github_token_masked") val githubTokenMasked: String? = null,
    @SerialName("github_token_create_url") val githubTokenCreateUrl: String = GitHubToken.CREATE_URL,
    val warning: String? = null,
    val curl: String = "",
    val scripts: ScriptBundleResponse,
    @SerialName("panel_scripts") val panelScripts: ScriptBundleResponse,
    val presets: OriginPresetsResponse,
    val links: List<ServiceLinkResponse>,
    val note: String = "",
)

@Serializable
data class SnapshotCdnResponse(
    val origin: String = "",
    val ok: Boolean = false,
)

@Serializable
data class InstallStampResponse(
    val version: String = "",
    @SerialName("installed_at") val installedAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class ScriptBundleResponse(
    val install: String = "",
    val update: String = "",
    val uninstall: String = "",
)

@Serializable
data class OriginPresetsResponse(
    val panel: String = "",
    val local: String = "",
    val prod: String = "",
)

@Serializable
data class ServiceLinkResponse(
    val id: String,
    val label: String,
    val url: String,
    val ok: Boolean = false,
)

fun Application.settingsApiRoutes(toolkit: Toolkit)
{
    routing {
        get("/api/settings") {
            val view = toolkit.getSettings(call.panelPublicOrigin())
            call.respond(view.toResponse())
        }

        put("/api/settings") {
            if (!toolkit.getAuthStatus(call.sessionToken()).authenticated)
            {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    AuthErrorResponse(error = "unauthorized", message = "Sign in at /login"),
                )
                return@put
            }
            val body = call.receive<SettingsPutBody>()
            when (val result = toolkit.saveSettings(body.toCommand(), call.panelPublicOrigin()))
            {
                is SaveSettingsResult.Ok -> call.respond(result.view.toResponse())
                SaveSettingsResult.OriginEmpty ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AuthErrorResponse(
                            error = "install_origin_required",
                            message = "Need an http(s) origin, e.g. http://127.0.0.1:8094",
                        ),
                    )
                SaveSettingsResult.OriginInvalid ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AuthErrorResponse(
                            error = "install_origin_invalid",
                            message = "Need an http(s) origin, e.g. http://127.0.0.1:8094",
                        ),
                    )
                SaveSettingsResult.SnapshotCdnOriginInvalid ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AuthErrorResponse(
                            error = "snapshot_cdn_origin_invalid",
                            message = "Need an http(s) origin, e.g. http://127.0.0.1:8095",
                        ),
                    )
                SaveSettingsResult.SnapshotCdnUnreachable ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        AuthErrorResponse(
                            error = "snapshot_cdn_unreachable",
                            message = "Snapshot CDN did not respond at the origin or /snapshots/. Start nginx + rpcnode-cdn, then save again.",
                        ),
                    )
                SaveSettingsResult.GitHubTokenInvalid ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AuthErrorResponse(
                            error = "github_token_invalid",
                            message = "GitHub rejected the token (401/403). Create a PAT (public repo read is enough): ${GitHubToken.CREATE_URL}",
                        ),
                    )
                is SaveSettingsResult.WriteFailed ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        AuthErrorResponse(error = "write_failed", message = result.reason),
                    )
            }
        }
    }
}

internal fun ApplicationCall.panelPublicOrigin(): String
{
    val host = request.headers[HttpHeaders.Host]?.trim().orEmpty().ifEmpty { "127.0.0.1:8094" }
    val proto = if (request.headers["X-Forwarded-Proto"].equals("https", ignoreCase = true))
    {
        "https"
    }
    else
    {
        "http"
    }
    return "$proto://$host"
}

private fun SettingsPutBody.toCommand() = SaveSettingsCommand(
    installOrigin = installOrigin,
    snapshotCdnOrigin = snapshotCdnOrigin,
    githubToken = githubToken,
    clearGithubToken = clearGithubToken,
)

private fun SettingsView.toResponse() = SettingsResponse(
    configured = configured,
    panelVersion = panelVersion,
    install = install?.toResponse(),
    installOrigin = channel.installOrigin,
    clientsBaseUrl = channel.clientsBaseUrl,
    installBaseUrl = channel.installBaseUrl,
    agentDownloadUrl = channel.agentDownloadUrl,
    snapshotCdnOrigin = snapshotCdn.origin,
    snapshotCdn = SnapshotCdnResponse(origin = snapshotCdn.origin, ok = snapshotCdn.ok),
    githubTokenSet = github.set,
    githubTokenDecryptOk = github.decryptOk,
    githubTokenMasked = github.masked.ifEmpty { null },
    warning = warning,
    curl = curl,
    scripts = scripts.toResponse(),
    panelScripts = panelScripts.toResponse(),
    presets = OriginPresetsResponse(
        panel = presets.panel,
        local = presets.local,
        prod = presets.prod,
    ),
    links = links.map { it.toResponse() },
    note = note,
)

private fun InstallStamp.toResponse() = InstallStampResponse(
    version = version,
    installedAt = installedAt,
    updatedAt = updatedAt,
)

private fun ScriptBundle.toResponse() = ScriptBundleResponse(
    install = install,
    update = update,
    uninstall = uninstall,
)

private fun ServiceLink.toResponse() = ServiceLinkResponse(
    id = id,
    label = label,
    url = url,
    ok = ok,
)
