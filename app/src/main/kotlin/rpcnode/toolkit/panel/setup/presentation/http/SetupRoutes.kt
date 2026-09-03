package rpcnode.toolkit.panel.setup.presentation.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import rpcnode.toolkit.panel.auth.presentation.http.AuthErrorResponse
import rpcnode.toolkit.panel.auth.presentation.http.AuthOkResponse
import rpcnode.toolkit.panel.auth.presentation.http.rfc3339
import rpcnode.toolkit.panel.auth.presentation.http.setSessionCookie
import rpcnode.toolkit.setup.application.create.CreateAdminResult
import rpcnode.toolkit.wiring.Toolkit

@Serializable
data class SetupStatusResponse(
    val ok: Boolean = true,
    val needed: Boolean,
)

@Serializable
data class SetupBody(
    val username: String = "",
    val email: String = "",
    val password: String = "",
)

fun Application.setupApiRoutes(toolkit: Toolkit)
{
    routing {
        get("/api/setup/status") {
            val status = toolkit.getSetupStatus()
            call.respond(SetupStatusResponse(needed = status.needed))
        }

        post("/api/setup") {
            val body = call.receive<SetupBody>()
            val user = body.username.ifBlank { body.email }
            when (val result = toolkit.createAdmin(user, body.password))
            {
                is CreateAdminResult.Created ->
                {
                    call.setSessionCookie(result.session)
                    call.respond(
                        AuthOkResponse(
                            user = result.session.username.value,
                            created = true,
                            token = result.session.token.value,
                            expiresAt = rfc3339(result.session),
                            message = "Admin created. You are signed in.",
                        ),
                    )
                }
                CreateAdminResult.AlreadyConfigured ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        AuthErrorResponse(error = "already_configured", message = "Panel user already exists — use /login"),
                    )
                CreateAdminResult.PasswordTooShort ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AuthErrorResponse(error = "password_too_short", message = "Password must be at least 8 characters"),
                    )
                CreateAdminResult.InvalidUsername ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AuthErrorResponse(error = "invalid_username", message = "Invalid username"),
                    )
                is CreateAdminResult.WriteFailed ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        AuthErrorResponse(error = "write_failed", message = result.reason),
                    )
            }
        }
    }
}
