package rpcnode.toolkit.agent.presentation.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.install
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("rpcnode.agent.error")

/**
 * A route that throws used to end as a bare «HTTP 500» with nothing readable on either side.
 * This logs the stack trace in the agent journal and answers with the reason plus the top frames
 * (`message`, `trace`), which the panel shows in the client-update dialog.
 *
 * Kept in the agent source set on purpose: `rpcnode-agent.jar` ships only the agent code and a short
 * list of main packages, so the panel's own copy of this plugin is not on the agent classpath.
 */
fun Application.installUnhandledErrors()
{
    install(
        createApplicationPlugin("AgentUnhandledErrors") {
            on(CallFailed) { call, cause ->
                if (cause is CancellationException)
                {
                    return@on
                }
                log.error("unhandled error in {} {}", call.request.httpMethod.value, call.request.uri, cause)
                if (call.response.status() != null)
                {
                    return@on
                }
                val frames = cause.stackTrace.take(12).joinToString("\n") { "  at $it" }
                val body = buildJsonObject {
                    put("ok", JsonPrimitive(false))
                    put("error", JsonPrimitive("internal_error"))
                    put("message", JsonPrimitive((cause.message ?: cause.javaClass.simpleName).take(600)))
                    put("trace", JsonPrimitive("${cause.javaClass.name}: ${cause.message}\n$frames"))
                }.toString()
                call.respondText(body, ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        },
    )
}
