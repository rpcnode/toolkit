package rpcnode.toolkit.panel.presentation.http

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rpcnode.toolkit.clients.application.validate.DetectPortConflictsUseCase
import rpcnode.toolkit.clients.application.validate.PortConflictUsage
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.panel.auth.presentation.http.authApiRoutes
import rpcnode.toolkit.panel.clients.presentation.http.clientsApiRoutes
import rpcnode.toolkit.panel.infrastructure.filesystem.ProjectServerDirectories
import rpcnode.toolkit.panel.infrastructure.filesystem.defaultServerLogFile
import rpcnode.toolkit.panel.infrastructure.log.applyDevLogLevels
import rpcnode.toolkit.panel.infrastructure.log.installServerFileLog
import rpcnode.toolkit.install.application.RenderAgentScriptUseCase
import rpcnode.toolkit.install.application.ServeInstallFileUseCase
import rpcnode.toolkit.panel.hosts.presentation.http.hostsApiRoutes
import rpcnode.toolkit.panel.install.presentation.http.installRoutes
import rpcnode.toolkit.panel.networks.presentation.http.networksApiRoutes
import rpcnode.toolkit.panel.nodes.presentation.http.nodesApiRoutes
import rpcnode.toolkit.panel.notifications.presentation.http.notificationsApiRoutes
import rpcnode.toolkit.servers.application.probe.ProbeHostAgentUseCase
import rpcnode.toolkit.servers.infrastructure.http.HttpHostAgentClient
import rpcnode.toolkit.panel.servers.presentation.http.agentIngestRoutes
import rpcnode.toolkit.panel.servers.presentation.http.serversApiRoutes
import rpcnode.toolkit.panel.settings.presentation.http.settingsApiRoutes
import rpcnode.toolkit.panel.setup.presentation.http.setupApiRoutes
import rpcnode.toolkit.wiring.Toolkit

fun main()
{
    val dirs = ProjectServerDirectories()
    val logFile = defaultServerLogFile(logDir = dirs.logDir())
    installServerFileLog(logFile)
    val cfg = ServerConfig()
    applyDevLogLevels(cfg.dev)
    val log = LoggerFactory.getLogger("rpcnode-server")
    log.info("rpcnode-server listening on {}:{}", cfg.listen, cfg.port)
    log.info("log file {}", logFile)
    log.info("HTTP access log → {}", logFile)
    val toolkit = Toolkit.production(cfg)
    logPortCatalogConflicts(log, toolkit.clientProgramCatalog.all())
    toolkit.resumeServerRemovals()
    embeddedServer(Netty, port = cfg.port, host = cfg.listen) {
        module(cfg, toolkit)
    }.start(wait = true)
}

fun Application.module(
    cfg: ServerConfig = ServerConfig(),
    toolkit: Toolkit = Toolkit.production(cfg),
)
{
    installHttpCallLogging(cfg.dev)
    installServerCors(cfg.corsOrigins)
    install(ContentNegotiation) {
        json(
            Json {
                encodeDefaults = true
                prettyPrint = false
                ignoreUnknownKeys = true
            },
        )
    }

    healthRoutes(cfg)
    hostsApiRoutes(toolkit)
    setupApiRoutes(toolkit)
    authApiRoutes(toolkit)
    settingsApiRoutes(toolkit)
    notificationsApiRoutes(toolkit)
    networksApiRoutes(toolkit)
    clientsApiRoutes(toolkit)
    val installRoot = Path.of(cfg.installDir)
    val script = RenderAgentScriptUseCase(installRoot)
    installRoutes(cfg, script, ServeInstallFileUseCase(installRoot))
    serversApiRoutes(ProbeHostAgentUseCase(HttpHostAgentClient()), toolkit, cfg) { script.version() }
    agentIngestRoutes(toolkit, logIngest = cfg.dev)
    nodesApiRoutes(toolkit)
    startClientUpdateNotifications(toolkit)
}

private fun Application.startClientUpdateNotifications(toolkit: Toolkit)
{
    val log = LoggerFactory.getLogger("rpcnode-client-update-notifications")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val worker = scope.launch {
        while (isActive)
        {
            try
            {
                when (val result = toolkit.notifyClientUpdates())
                {
                    is rpcnode.toolkit.notifications.application.NotifyClientUpdatesResult.Completed ->
                    {
                        if (result.sent > 0)
                        {
                            log.info("sent {} client update notification(s)", result.sent)
                        }
                    }
                    rpcnode.toolkit.notifications.application.NotifyClientUpdatesResult.GitHubTokenMissing ->
                        log.debug("client update check skipped: GitHub token is not configured")
                    rpcnode.toolkit.notifications.application.NotifyClientUpdatesResult.NotificationsDisabled -> Unit
                }
            }
            catch (e: Exception)
            {
                log.warn("client update notification check failed", e)
            }
            delay(10.minutes)
        }
    }
    monitor.subscribe(ApplicationStopped) {
        worker.cancel()
    }
}

/** Catches a catalog authoring mistake early: two network/env pairs fixed to the same port
 *  can never run as separate nodes on the same host. */
private fun logPortCatalogConflicts(log: Logger, programs: List<ClientProgramSpec>)
{
    val conflicts = DetectPortConflictsUseCase()(programs)
    if (conflicts.isEmpty())
    {
        log.info("client program ports catalog: no conflicts across {} program(s)", programs.size)
        return
    }
    for (conflict in conflicts)
    {
        log.warn("port catalog conflict: port {} is fixed for {}", conflict.port, conflict.usedBy.describe())
    }
}

private fun List<PortConflictUsage>.describe(): String =
    joinToString(", ") { "${it.network.value}/${it.env.value} (${it.programId}:${it.role})" }
