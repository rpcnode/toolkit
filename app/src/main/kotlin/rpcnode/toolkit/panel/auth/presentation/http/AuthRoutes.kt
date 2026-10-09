package rpcnode.toolkit.panel.auth.presentation.http

import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rpcnode.toolkit.auth.application.login.LoginResult
import rpcnode.toolkit.auth.application.status.AuthStatus
import rpcnode.toolkit.auth.domain.model.Session
import rpcnode.toolkit.auth.domain.model.SessionToken
import rpcnode.toolkit.wiring.Toolkit

const val SESSION_COOKIE = "rpcnode_session"

@Serializable
data class AuthStatusResponse(
    val ok: Boolean = true,
    val authenticated: Boolean,
    val user: String,
    @SerialName("session_ttl_hours") val sessionTtlHours: Int = 24,
)

@Serializable
data class AuthOkResponse(
    val ok: Boolean = true,
    val authenticated: Boolean = true,
    val user: String,
    val created: Boolean = false,
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
    val message: String? = null,
)

@Serializable
data class AuthErrorResponse(
    val ok: Boolean = false,
    val error: String,
    val message: String? = null,
)

@Serializable
data class LogoutResponse(
    val ok: Boolean = true,
    val authenticated: Boolean = false,
)

@Serializable
data class AuthBody(
    val username: String = "",
    val email: String = "",
    val password: String = "",
)

fun Application.authApiRoutes(toolkit: Toolkit)
{
    routing {
        get("/api/auth/status") {
            val status = toolkit.getAuthStatus(call.sessionToken())
            call.respond(status.toResponse())
        }

        post("/api/auth/login") {
            val body = call.receive<AuthBody>()
            val user = body.username.ifBlank { body.email }
            when (val result = toolkit.login(user, body.password))
            {
                is LoginResult.Ok ->
                {
                    call.setSessionCookie(result.session)
                    call.respond(
                        AuthOkResponse(
                            user = result.session.username.value,
                            token = result.session.token.value,
                            expiresAt = rfc3339(result.session),
                        ),
                    )
                }
                LoginResult.NeedsSetup ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        AuthErrorResponse(error = "needs_setup", message = "No panel user yet — open /setup"),
                    )
                LoginResult.MissingFields ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AuthErrorResponse(error = "username_and_password_required"),
                    )
                LoginResult.InvalidCredentials ->
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        AuthErrorResponse(error = "invalid_credentials"),
                    )
            }
        }

        post("/api/auth/logout") {
            toolkit.logout(call.sessionToken())
            call.clearSessionCookie()
            call.respond(LogoutResponse())
        }
    }
}

internal fun ApplicationCall.sessionToken(): SessionToken?
{
    val cookie = request.cookies[SESSION_COOKIE]
    if (!cookie.isNullOrBlank())
    {
        return SessionToken(cookie)
    }
    val header = request.headers[HttpHeaders.Authorization].orEmpty()
    if (header.startsWith("Bearer "))
    {
        val raw = header.removePrefix("Bearer ").trim()
        if (raw.isNotEmpty())
        {
            return SessionToken(raw)
        }
    }
    return null
}

internal fun ApplicationCall.clearSessionCookie()
{
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE,
            value = "",
            path = "/",
            httpOnly = true,
            maxAge = 0,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

internal fun ApplicationCall.setSessionCookie(session: Session)
{
    val maxAge = Duration.between(Instant.now(), session.expiresAt).seconds
        .coerceIn(0, Session.TTL.seconds)
        .toInt()
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE,
            value = session.token.value,
            path = "/",
            httpOnly = true,
            maxAge = maxAge,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

private fun AuthStatus.toResponse() = AuthStatusResponse(
    authenticated = authenticated,
    user = user?.value ?: "",
)

internal fun rfc3339(session: Session): String =
    DateTimeFormatter.ISO_INSTANT.format(session.expiresAt)
