package rpcnode.toolkit.panel.proxies.presentation.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rpcnode.toolkit.nodes.application.proxy.SetNodeProxyResult
import rpcnode.toolkit.panel.auth.presentation.http.AuthErrorResponse
import rpcnode.toolkit.panel.auth.presentation.http.sessionToken
import rpcnode.toolkit.proxies.application.DeleteProxyResult
import rpcnode.toolkit.proxies.application.ProxyView
import rpcnode.toolkit.proxies.application.SaveProxyResult
import rpcnode.toolkit.proxies.domain.model.ProxyCheck
import rpcnode.toolkit.wiring.Toolkit

@Serializable
data class ProxyResponse(
    val id: String,
    val name: String,
    /** Password masked as `***`; send it back unchanged on edit to keep the stored one. */
    val url: String,
    /** Names of nodes using this proxy. */
    val nodes: List<String> = emptyList(),
)

@Serializable
data class ProxiesListResponse(val items: List<ProxyResponse> = emptyList())

@Serializable
data class ProxySaveBody(
    val name: String = "",
    val url: String = "",
    /** Save even when the connect check fails (proxy is temporarily down). */
    @SerialName("skip_check") val skipCheck: Boolean = false,
    /** Check from this host's agent instead of the panel (the proxy may be allow-listed for the server only). */
    @SerialName("server_id") val serverId: String? = null,
)

@Serializable
data class ProxyTestBody(
    val id: String? = null,
    val url: String = "",
    @SerialName("server_id") val serverId: String? = null,
)

@Serializable
data class ProxyTestResponse(
    val ok: Boolean,
    @SerialName("latency_ms") val latencyMs: Long = 0,
    val message: String = "",
)

@Serializable
data class ProxyErrorResponse(
    val ok: Boolean = false,
    val error: String,
    val message: String = "",
    val nodes: List<String> = emptyList(),
)

@Serializable
data class NodeProxyBody(@SerialName("proxy_id") val proxyId: String? = null)

@Serializable
data class NodeProxyResponse(
    val ok: Boolean = true,
    @SerialName("node_id") val nodeId: String,
    @SerialName("proxy_id") val proxyId: String,
)

/** Settings → Proxies (global list) and the node's pick from it. Session required: URLs carry proxy passwords. */
fun Application.proxiesApiRoutes(toolkit: Toolkit)
{
    routing {
        get("/api/proxies") {
            if (!call.signedIn(toolkit)) return@get
            call.respond(ProxiesListResponse(toolkit.listProxies().map { it.toResponse() }))
        }

        post("/api/proxies") {
            if (!call.signedIn(toolkit)) return@post
            val body = call.receive<ProxySaveBody>()
            call.respondSave(toolkit, toolkit.saveProxy(id = null, name = body.name, url = body.url, skipCheck = body.skipCheck, serverId = body.serverId))
        }

        put("/api/proxies/{id}") {
            if (!call.signedIn(toolkit)) return@put
            val body = call.receive<ProxySaveBody>()
            call.respondSave(toolkit, toolkit.saveProxy(id = call.parameters["id"].orEmpty(), name = body.name, url = body.url, skipCheck = body.skipCheck, serverId = body.serverId))
        }

        post("/api/proxies/test") {
            if (!call.signedIn(toolkit)) return@post
            val body = call.receive<ProxyTestBody>()
            when (val check = toolkit.testProxy(body.id, body.url, body.serverId))
            {
                is ProxyCheck.Ok ->
                    call.respond(ProxyTestResponse(ok = true, latencyMs = check.latencyMs, message = "Reached ${check.target} through the proxy"))
                is ProxyCheck.Failed ->
                    call.respond(ProxyTestResponse(ok = false, message = check.reason))
            }
        }

        delete("/api/proxies/{id}") {
            if (!call.signedIn(toolkit)) return@delete
            when (val result = toolkit.deleteProxy(call.parameters["id"].orEmpty()))
            {
                DeleteProxyResult.Ok -> call.respond(ProxiesListResponse(toolkit.listProxies().map { it.toResponse() }))
                DeleteProxyResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ProxyErrorResponse(error = "not_found"))
                is DeleteProxyResult.InUse ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        ProxyErrorResponse(
                            error = "in_use",
                            message = "Used by ${result.nodes.joinToString()} — switch those nodes to another proxy first",
                            nodes = result.nodes,
                        ),
                    )
            }
        }

        put("/api/nodes/{id}/proxy") {
            if (!call.signedIn(toolkit)) return@put
            val body = call.receive<NodeProxyBody>()
            when (val result = toolkit.setNodeProxy(call.parameters["id"].orEmpty(), body.proxyId))
            {
                is SetNodeProxyResult.Saved -> call.respond(NodeProxyResponse(nodeId = result.nodeId, proxyId = result.proxyId))
                SetNodeProxyResult.NodeNotFound ->
                    call.respond(HttpStatusCode.NotFound, ProxyErrorResponse(error = "not_found"))
                SetNodeProxyResult.ProxyNotFound ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ProxyErrorResponse(error = "unknown_proxy", message = "No such proxy in Settings → Proxies"),
                    )
            }
        }
    }
}

private suspend fun ApplicationCall.respondSave(toolkit: Toolkit, result: SaveProxyResult)
{
    when (result)
    {
        is SaveProxyResult.Ok ->
            respond(toolkit.listProxies().first { it.id == result.proxy.id.value }.toResponse())
        is SaveProxyResult.InvalidUrl ->
            respond(HttpStatusCode.BadRequest, ProxyErrorResponse(error = "invalid_url", message = result.reason))
        is SaveProxyResult.Unreachable ->
            respond(
                HttpStatusCode.UnprocessableEntity,
                ProxyErrorResponse(error = "proxy_unreachable", message = "Proxy is not reachable: ${result.reason}"),
            )
        SaveProxyResult.NotFound ->
            respond(HttpStatusCode.NotFound, ProxyErrorResponse(error = "not_found"))
    }
}

private suspend fun ApplicationCall.signedIn(toolkit: Toolkit): Boolean
{
    if (toolkit.getAuthStatus(sessionToken()).authenticated)
    {
        return true
    }
    respond(HttpStatusCode.Unauthorized, AuthErrorResponse(error = "unauthorized", message = "Sign in at /login"))
    return false
}

private fun ProxyView.toResponse() = ProxyResponse(id = id, name = name, url = url, nodes = nodes)
