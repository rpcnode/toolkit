package rpcnode.toolkit.panel.presentation.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond

/**
 * Browser preflight (OPTIONS + JSON POST) is not covered by a GET Origin check.
 * Echo the request origin when it is allowed so cookies can cross
 * localhost ↔ 127.0.0.1.
 *
 * Loopback Origins are always allowed (Vite often lands on :5174/:5175 when :5173
 * is taken). Remote Origins must still match [PANEL_CORS_ORIGINS].
 */
fun Application.installServerCors(origins: List<String>)
{
    val allowAny = origins.isEmpty()
    val allowed = origins.toSet()
    intercept(ApplicationCallPipeline.Setup) {
        val origin = call.request.header(HttpHeaders.Origin) ?: return@intercept
        if (!allowAny && !originAllowed(origin, allowed))
        {
            if (call.request.httpMethod == HttpMethod.Options)
            {
                call.respond(HttpStatusCode.Forbidden)
                finish()
            }
            return@intercept
        }
        call.response.header(HttpHeaders.AccessControlAllowOrigin, origin)
        call.response.header(HttpHeaders.AccessControlAllowCredentials, "true")
        call.response.header(HttpHeaders.Vary, HttpHeaders.Origin)
        if (call.request.httpMethod == HttpMethod.Options)
        {
            call.response.header(
                HttpHeaders.AccessControlAllowMethods,
                "GET, POST, PUT, DELETE, OPTIONS",
            )
            val want = call.request.header(HttpHeaders.AccessControlRequestHeaders)
            call.response.header(
                HttpHeaders.AccessControlAllowHeaders,
                want?.ifBlank { null } ?: HttpHeaders.ContentType,
            )
            call.response.header(HttpHeaders.AccessControlMaxAge, "600")
            call.respond(HttpStatusCode.NoContent)
            finish()
        }
    }
}

internal fun originAllowed(origin: String, allowed: Set<String>): Boolean
{
    // Local admin / Vite — any port. Browser Origin cannot be forged cross-site.
    if (isLoopbackHttpOrigin(origin))
    {
        return true
    }
    if (origin in allowed)
    {
        return true
    }
    val swapped = when
    {
        "://localhost" in origin -> origin.replace("://localhost", "://127.0.0.1")
        "://127.0.0.1" in origin -> origin.replace("://127.0.0.1", "://localhost")
        else -> null
    }
    return swapped != null && swapped in allowed
}

internal fun isLoopbackHttpOrigin(origin: String): Boolean
{
    return try
    {
        val u = java.net.URI(origin)
        val host = (u.host ?: "").lowercase()
        val scheme = (u.scheme ?: "").lowercase()
        (scheme == "http" || scheme == "https") &&
            (host == "localhost" || host == "127.0.0.1" || host == "::1")
    }
    catch (_: Exception)
    {
        false
    }
}
