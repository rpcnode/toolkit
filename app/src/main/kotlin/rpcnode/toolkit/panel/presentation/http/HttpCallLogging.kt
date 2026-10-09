package rpcnode.toolkit.panel.presentation.http

import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import rpcnode.toolkit.shared.infrastructure.log.HttpIoLog

private val DevReqBodyKey = AttributeKey<String>("dev-req-body")
private val DevResBodyKey = AttributeKey<String>("dev-res-body")
private const val MAX_BODY = 1500

/**
 * Method, URI, status, duration, remote host — no headers (so no Authorization).
 * `RPCNODE_DEV`: also request/response bodies (secrets redacted) so IDEA shows admin API traffic.
 */
fun Application.installHttpCallLogging(dev: Boolean = false)
{
    if (dev)
    {
        install(DoubleReceive)
        install(devHttpBodiesPlugin)
    }
    install(CallLogging) {
        level = Level.INFO
        logger = LoggerFactory.getLogger("rpcnode.http")
        format { call ->
            val status = call.response.status()?.value ?: 0
            val method = call.request.httpMethod.value
            val uri = call.request.uri
            val ms = call.processingTimeMillis()
            val from = call.request.local.remoteHost
            val line = HttpIoLog.inbound(method, uri, status, ms, from)
            if (!dev || !uri.startsWith("/api/"))
            {
                return@format line
            }
            val req = call.attributes.getOrNull(DevReqBodyKey).orEmpty()
            val res = call.attributes.getOrNull(DevResBodyKey).orEmpty()
            buildString {
                append(line)
                if (req.isNotEmpty())
                {
                    append(" req=").append(req)
                }
                if (res.isNotEmpty())
                {
                    append(" res=").append(res)
                }
            }
        }
    }
}

private val devHttpBodiesPlugin = createApplicationPlugin("DevHttpBodies") {
    onCall { call ->
        val method = call.request.httpMethod.value
        if (method == "POST" || method == "PUT" || method == "PATCH")
        {
            val raw = runCatching { call.receiveText() }.getOrDefault("")
            if (raw.isNotEmpty())
            {
                call.attributes.put(DevReqBodyKey, redactSecrets(raw).take(MAX_BODY))
            }
        }
    }
    onCallRespond { call ->
        transformBody { data ->
            val rendered = data.toString()
            if (rendered.isNotEmpty() &&
                rendered != "kotlin.Unit" &&
                !rendered.startsWith("io.ktor.")
            )
            {
                call.attributes.put(DevResBodyKey, redactSecrets(rendered).take(MAX_BODY))
            }
            data
        }
    }
}

internal fun redactSecrets(raw: String): String =
    raw
        .replace(
            Regex(
                """"(password|token|agent_key|github_token|githubToken|notify_key)"\s*:\s*"[^"]*"""",
                RegexOption.IGNORE_CASE,
            ),
            """"$1":"…"""",
        )
        .replace(
            Regex(
                """(password|token|agentKey|agent_key|githubToken)=[^,)\s]+""",
                RegexOption.IGNORE_CASE,
            ),
            "$1=…",
        )
