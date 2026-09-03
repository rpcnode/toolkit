package rpcnode.toolkit.panel.presentation.http

import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.healthRoutes(cfg: ServerConfig)
{
    routing {
        get("/healthz") {
            call.respond(
                HealthzResponse(
                    ok = true,
                    alive = true,
                    role = "server",
                    port = cfg.port,
                    db = cfg.dbPath,
                ),
            )
        }
    }
}
