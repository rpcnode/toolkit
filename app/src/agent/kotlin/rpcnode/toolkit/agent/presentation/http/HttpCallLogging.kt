package rpcnode.toolkit.agent.presentation.http

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import rpcnode.toolkit.agent.infrastructure.log.HttpIoLog

/** Method, URI, status, duration, remote host — no headers (so no Authorization). */
fun Application.installHttpCallLogging()
{
    install(CallLogging) {
        level = Level.INFO
        logger = LoggerFactory.getLogger("rpcnode.http")
        format { call ->
            val status = call.response.status()?.value ?: 0
            val method = call.request.httpMethod.value
            val uri = call.request.uri
            val ms = call.processingTimeMillis()
            val from = call.request.local.remoteHost
            HttpIoLog.inbound(method, uri, status, ms, from)
        }
    }
}
