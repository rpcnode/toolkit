package rpcnode.toolkit.panel.presentation.http

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

private val log = LoggerFactory.getLogger("rpcnode.http.error")

/**
 * A route that throws used to end as a bare "HTTP 500" in the admin and nothing readable in the journal.
 * This logs the stack trace once and answers with the reason, in the same JSON shape the routes use
 * (`ok`, `error`, `message`), so the admin shows what actually failed.
 */
fun Application.installUnhandledErrors()
{
    install(
        createApplicationPlugin("UnhandledErrors") {
            on(CallFailed) { call, cause ->
                if (cause is CancellationException)
                {
                    return@on
                }
                val where = "${call.request.httpMethod.value} ${call.request.uri}"
                log.error("unhandled error in {}", where, cause)
                if (call.response.status() != null)
                {
                    return@on
                }
                val reason = (cause.message ?: cause.javaClass.simpleName).take(600)
                val frames = cause.stackTrace.take(12).joinToString("\n") { "  at $it" }
                val body = buildJsonObject {
                    put("ok", JsonPrimitive(false))
                    put("error", JsonPrimitive("internal_error"))
                    put("message", JsonPrimitive(reason))
                    put("trace", JsonPrimitive("${cause.javaClass.name}: ${cause.message}\n$frames"))
                }.toString()
                call.respondText(body, ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        },
    )
}
