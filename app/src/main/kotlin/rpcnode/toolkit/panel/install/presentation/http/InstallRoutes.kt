package rpcnode.toolkit.panel.install.presentation.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.host
import io.ktor.server.request.port
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import rpcnode.toolkit.install.application.RenderAgentScriptUseCase
import rpcnode.toolkit.install.application.ServeInstallFileUseCase
import rpcnode.toolkit.panel.presentation.http.ServerConfig

@Serializable
data class AgentChannelResponse(
    val ok: Boolean = true,
    val version: String = "",
    val channel: String = "panel",
    val install_url: String = "",
    val binaries_base: String = "",
)

fun Application.installRoutes(
    cfg: ServerConfig,
    renderScript: RenderAgentScriptUseCase,
    serveFile: ServeInstallFileUseCase,
)
{
    routing {
        get("/install/version") {
            val version = renderScript.version()
            if (version.isBlank())
            {
                call.respond(HttpStatusCode.NotFound, "chainAgentVersion is missing from the panel JAR\n")
                return@get
            }
            call.respondText(version + "\n", ContentType.Text.Plain)
        }

        get("/install/{path...}") {
            val rel = call.parameters.getAll("path")?.joinToString("/") ?: ""
            val file = serveFile(rel)
            if (file == null)
            {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.respondFile(file.toFile())
        }

        get("/api/agent/channel") {
            val origin = installOrigin(call, cfg)
            val version = renderScript.version()
            call.respond(
                AgentChannelResponse(
                    version = version,
                    install_url = "$origin/install/binaries/rpcnode-agent.jar",
                    binaries_base = "$origin/install/binaries",
                ),
            )
        }
    }
}

internal fun installOrigin(call: ApplicationCall, cfg: ServerConfig): String
{
    val override = cfg.installOriginOverride?.trim()?.trimEnd('/')
    if (!override.isNullOrBlank())
    {
        return override.removeSuffix("/install").trimEnd('/')
    }
    val forwardedProto = call.request.headers["X-Forwarded-Proto"]?.trim()?.ifEmpty { null }
    val forwardedHost = call.request.headers["X-Forwarded-Host"]?.trim()?.ifEmpty { null }
    if (forwardedProto != null && forwardedHost != null)
    {
        return "$forwardedProto://$forwardedHost"
    }
    val host = call.request.host()
    val port = call.request.port()
    val suffix = if (port == 80 || port == 443) "" else ":$port"
    return "http://$host$suffix"
}
