package rpcnode.toolkit.panel.setup.presentation.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rpcnode.toolkit.panel.auth.presentation.http.AuthErrorResponse
import rpcnode.toolkit.panel.auth.presentation.http.AuthOkResponse
import rpcnode.toolkit.panel.auth.presentation.http.rfc3339
import rpcnode.toolkit.panel.auth.presentation.http.sessionToken
import rpcnode.toolkit.panel.auth.presentation.http.setSessionCookie
import rpcnode.toolkit.setup.application.check.SetupCheckItem
import rpcnode.toolkit.setup.application.create.CreateAdminResult
import rpcnode.toolkit.setup.application.finish.FinishSetupResult
import rpcnode.toolkit.setup.application.stage.SetSetupStageResult
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

@Serializable
data class SetupCheckResponse(
    val ok: Boolean = true,
    val ready: Boolean,
    val checks: List<SetupCheckItemResponse>,
)

@Serializable
data class SetupCheckItemResponse(
    val id: String,
    val label: String,
    val ok: Boolean,
    val required: Boolean = false,
    val detail: String = "",
)

@Serializable
data class SetupStageBody(
    val stage: String = "",
)

@Serializable
data class SetupStageResponse(
    val ok: Boolean = true,
    @SerialName("setup_stage") val setupStage: String,
)

@Serializable
data class SetupFinishResponse(
    val ok: Boolean = true,
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
                            created = !result.updated,
                            token = result.session.token.value,
                            expiresAt = rfc3339(result.session),
                            message = if (result.updated)
                            {
                                "Password updated. You are signed in."
                            }
                            else
                            {
                                "Admin created. You are signed in."
                            },
                        ),
                    )
                }
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

        get("/api/setup/check") {
            if (!call.requireSetupAuth(toolkit)) return@get
            val result = toolkit.runSetupCheck()
            call.respond(
                SetupCheckResponse(
                    ready = result.ready,
                    checks = result.checks.map { it.toResponse() },
                ),
            )
        }

        post("/api/setup/stage") {
            if (!call.requireSetupAuth(toolkit)) return@post
            val body = call.receive<SetupStageBody>()
            when (val result = toolkit.setSetupStage(body.stage))
            {
                is SetSetupStageResult.Ok ->
                    call.respond(SetupStageResponse(setupStage = result.stage))
                SetSetupStageResult.Invalid ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AuthErrorResponse(error = "invalid_stage", message = "stage must be admin|server|networks|done"),
                    )
            }
        }

        post("/api/setup/finish") {
            if (!call.requireSetupAuth(toolkit)) return@post
            when (val result = toolkit.finishSetup())
            {
                FinishSetupResult.Ok -> call.respond(SetupFinishResponse())
                is FinishSetupResult.WriteFailed ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        AuthErrorResponse(error = "write_failed", message = result.reason),
                    )
            }
        }
    }
}

private suspend fun ApplicationCall.requireSetupAuth(toolkit: Toolkit): Boolean
{
    if (toolkit.getAuthStatus(sessionToken()).authenticated)
    {
        return true
    }
    respond(
        HttpStatusCode.Unauthorized,
        AuthErrorResponse(error = "unauthorized", message = "Sign in at /login"),
    )
    return false
}

private fun SetupCheckItem.toResponse() = SetupCheckItemResponse(
    id = id,
    label = label,
    ok = ok,
    required = required,
    detail = detail,
)
