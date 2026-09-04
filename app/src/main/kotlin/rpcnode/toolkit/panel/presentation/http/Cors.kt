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
 * Echo the request origin when it is on the allowlist so cookies can cross
 * localhost ↔ 127.0.0.1.
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
    if (origin in allowed)
    {
        return true
    }
    val swapped = when
    {
        "://localhost" in origin -> origin.replace("://localhost", "://127.0.0.1")
        "://127.0.0.1" in origin -> origin.replace("://127.0.0.1", "://localhost")
        else -> return false
    }
    return swapped in allowed
}
