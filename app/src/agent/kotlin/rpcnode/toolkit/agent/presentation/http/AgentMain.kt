package rpcnode.toolkit.agent.presentation.http

import java.nio.file.Path
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rpcnode.toolkit.agent.application.client.SyncClientFromPanelUseCase
import rpcnode.toolkit.agent.application.client.UpdateClientOnHostUseCase
import rpcnode.toolkit.agent.application.disks.GetHostDisksUseCase
import rpcnode.toolkit.agent.application.sysctl.GetHostSysctlUseCase
import rpcnode.toolkit.agent.infrastructure.proc.SolanaHostSysctlProbe
import rpcnode.toolkit.agent.application.enroll.EnrollPanelUseCase
import rpcnode.toolkit.agent.application.enroll.ProbePanel
import rpcnode.toolkit.agent.application.enroll.UnenrollPanelUseCase
import rpcnode.toolkit.agent.application.metrics.CollectHostMetricsUseCase
import rpcnode.toolkit.agent.application.node.ChainNodeRuntime
import rpcnode.toolkit.agent.application.node.ControlNodeUnitUseCase
import rpcnode.toolkit.agent.application.node.GetNodeClientVersionUseCase
import rpcnode.toolkit.agent.application.node.GetNodeProcessLogsUseCase
import rpcnode.toolkit.agent.application.node.NodeHeightPusher
import rpcnode.toolkit.agent.application.node.PushNodeHeightsUseCase
import rpcnode.toolkit.agent.application.node.RemoveNodeHostUseCase
import rpcnode.toolkit.agent.application.node.StartNodeProcessUseCase
import rpcnode.toolkit.agent.application.ports.CheckPortsUseCase
import rpcnode.toolkit.agent.application.push.HostMetricsPusher
import rpcnode.toolkit.agent.application.push.PushHostMetricsUseCase
import rpcnode.toolkit.agent.application.snapshot.GetSnapshotProgressUseCase
import rpcnode.toolkit.agent.application.snapshot.ProbeSnapshotSpeedUseCase
import rpcnode.toolkit.agent.application.snapshot.StartSnapshotDownloadUseCase
import rpcnode.toolkit.agent.infrastructure.http.HttpSnapshotSpeedProbe
import rpcnode.toolkit.agent.application.status.GetAgentIdentityUseCase
import rpcnode.toolkit.agent.application.update.AgentInstallResult
import rpcnode.toolkit.agent.application.update.AgentJarInstaller
import rpcnode.toolkit.agent.application.update.AgentReleaseChannel
import rpcnode.toolkit.agent.application.update.AgentRestarter
import rpcnode.toolkit.agent.application.update.UpdateAgentUseCase
import rpcnode.toolkit.agent.infrastructure.config.HostClientConfigPatch
import rpcnode.toolkit.agent.infrastructure.enroll.FilePanelEnrollmentStore
import rpcnode.toolkit.agent.infrastructure.enroll.InMemoryPanelEnrollmentStore
import rpcnode.toolkit.agent.infrastructure.filesystem.FileSnapshotJobStore
import rpcnode.toolkit.agent.infrastructure.filesystem.ProjectAgentDirectories
import rpcnode.toolkit.agent.infrastructure.filesystem.defaultAgentLogFile
import rpcnode.toolkit.agent.infrastructure.http.HttpAgentJarInstaller
import rpcnode.toolkit.agent.infrastructure.http.HttpAgentReleaseChannel
import rpcnode.toolkit.agent.infrastructure.http.HttpPanelMetricsClient
import rpcnode.toolkit.agent.infrastructure.http.HttpPanelNodeEventsClient
import rpcnode.toolkit.agent.infrastructure.http.HttpPanelReachabilityClient
import rpcnode.toolkit.agent.infrastructure.http.SystemdAgentRestarter
import rpcnode.toolkit.agent.infrastructure.log.applyDevLogLevels
import rpcnode.toolkit.agent.infrastructure.log.installAgentFileLog
import rpcnode.toolkit.agent.infrastructure.net.TcpPortProbe
import rpcnode.toolkit.agent.infrastructure.node.FileRunningNodeRegistry
import rpcnode.toolkit.agent.infrastructure.proc.LsblkHostDiskProbe
import rpcnode.toolkit.agent.infrastructure.proc.LinuxHostMetrics
import rpcnode.toolkit.agent.infrastructure.proc.runningAsRoot
import rpcnode.toolkit.chains.arb.infrastructure.http.ArbNodeHeightProbe
import rpcnode.toolkit.chains.arb.infrastructure.proc.ArbNodeProcessStarter
import rpcnode.toolkit.chains.base.infrastructure.http.BaseNodeHeightProbe
import rpcnode.toolkit.chains.base.infrastructure.proc.BaseNodeProcessStarter
import rpcnode.toolkit.chains.bitcore.infrastructure.BitcoreChainSpecs
import rpcnode.toolkit.chains.bitcore.infrastructure.BitcoreNodeHeightProbe
import rpcnode.toolkit.chains.bitcore.infrastructure.BitcoreNodeProcessStarter
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumNodeHeightProbe
import rpcnode.toolkit.chains.ethereum.infrastructure.proc.EthereumNodeProcessStarter
import rpcnode.toolkit.chains.bsc.infrastructure.http.BscNodeHeightProbe
import rpcnode.toolkit.chains.bsc.infrastructure.proc.BscNodeProcessStarter
import rpcnode.toolkit.chains.polygon.infrastructure.http.PolygonNodeHeightProbe
import rpcnode.toolkit.chains.polygon.infrastructure.proc.PolygonNodeProcessStarter
import rpcnode.toolkit.chains.solana.infrastructure.http.SolanaNodeHeightProbe
import rpcnode.toolkit.chains.solana.infrastructure.proc.SolanaNodeProcessStarter
import rpcnode.toolkit.chains.sui.infrastructure.http.SuiNodeHeightProbe
import rpcnode.toolkit.chains.sui.infrastructure.proc.SuiNodeProcessStarter
import rpcnode.toolkit.chains.xrpl.infrastructure.http.XrplNodeHeightProbe
import rpcnode.toolkit.chains.xrpl.infrastructure.proc.XrplNodeProcessStarter
import rpcnode.toolkit.chains.hyperliquid.infrastructure.http.HyperliquidNodeHeightProbe
import rpcnode.toolkit.chains.hyperliquid.infrastructure.proc.HyperliquidNodeProcessStarter
import rpcnode.toolkit.chains.ton.infrastructure.http.TonNodeHeightProbe
import rpcnode.toolkit.chains.ton.infrastructure.proc.TonNodeProcessStarter
import rpcnode.toolkit.chains.tron.infrastructure.http.TronNodeHeightProbe
import rpcnode.toolkit.chains.tron.infrastructure.proc.TronNodeProcessStarter
import rpcnode.toolkit.chains.zcash.infrastructure.http.ZcashNodeHeightProbe
import rpcnode.toolkit.chains.zcash.infrastructure.proc.ZcashNodeProcessStarter

fun main(args: Array<String>)
{
    val cmd = args.firstOrNull()?.trim()?.lowercase().orEmpty()
    when (cmd)
    {
        "install" -> kotlin.system.exitProcess(AgentSystemInstall.install())
        "update", "upgrade", "reinstall" -> kotlin.system.exitProcess(AgentSystemInstall.update())
        "uninstall", "remove" -> kotlin.system.exitProcess(AgentSystemInstall.uninstall())
        "help", "-h", "--help" ->
        {
            printAgentHelp(versionFromGradle())
            return
        }
        "" -> Unit
        else ->
        {
            System.err.println("unknown command: $cmd")
            printAgentHelp(versionFromGradle())
            kotlin.system.exitProcess(2)
        }
    }
    runAgentServer()
}

private fun printAgentHelp(version: String)
{
    val origin = "\$ORIGIN"
    println(
        """
        rpcnode-agent $version — host agent

          curl -fsSL -o rpcnode-agent.jar "$origin/install/binaries/rpcnode-agent.jar"
          sudo java -jar rpcnode-agent.jar install

          sudo java -jar rpcnode-agent.jar update
          sudo java -jar rpcnode-agent.jar uninstall
          java -jar rpcnode-agent.jar              # run daemon (token via AGENT_TOKEN_FILE)
          java -jar rpcnode-agent.jar help
        """.trimIndent(),
    )
}

private fun runAgentServer()
{
    val dirs = ProjectAgentDirectories()
    val logFile = defaultAgentLogFile(logDir = dirs.logDir())
    installAgentFileLog(logFile)
    val cfg = AgentConfig(
        tokenFile = AgentConfig.defaultTokenFile(
            configDir = dirs.configDir(),
        ),
    )
    applyDevLogLevels(cfg.dev)
    val log = LoggerFactory.getLogger("rpcnode-agent")
    val reserved = reserveAgentPortsOnStart()
    if (reserved.ok)
    {
        log.info("ports {}", reserved.detail)
    }
    else
    {
        log.warn("ports {}", reserved.detail)
    }
    if (cfg.token.isBlank())
    {
        log.error("token file is empty or missing: {}", cfg.tokenFile)
        log.error("set AGENT_TOKEN_FILE to a readable token file")
        kotlin.system.exitProcess(1)
    }
    val collectMetrics = CollectHostMetricsUseCase(LinuxHostMetrics())
    val enrollment = FilePanelEnrollmentStore(dirs.configDir().resolve("panel.json"))
    val pushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    HostMetricsPusher(
        push = PushHostMetricsUseCase(
            enrollment = enrollment,
            collect = collectMetrics,
            client = HttpPanelMetricsClient(logRequests = cfg.dev, agentVersion = cfg.version),
            token = cfg.token,
        ),
        scope = pushScope,
    ).start()
    val busy = listenPortBusyMessage(cfg.listen, cfg.port)
    if (busy != null)
    {
        failListenPort(log, busy)
    }
    val enrollPanel = EnrollPanelUseCase(enrollment, HttpPanelReachabilityClient(logRequests = cfg.dev))
    val unenrollPanel = UnenrollPanelUseCase(enrollment)
    val updateAgent = UpdateAgentUseCase(
        localVersion = cfg.version,
        resolvePanelUrl = {
            enrollment.read()?.panelUrl?.ifBlank { null }
                ?: System.getenv("INSTALL_ORIGIN")?.trim()?.trimEnd('/')?.ifEmpty { null }
        },
        channel = HttpAgentReleaseChannel(),
        installer = HttpAgentJarInstaller(),
        restarter = SystemdAgentRestarter(),
    )
    val snapshotStore = FileSnapshotJobStore(dirs.cacheDir().resolve("snapshots"))
    val startSnapshot = StartSnapshotDownloadUseCase(
        store = snapshotStore,
        downloadRoot = dirs.cacheDir().resolve("snapshot-downloads"),
        scope = pushScope,
    )
    startSnapshot.recoverInterrupted()
    val getSnapshotProgress = GetSnapshotProgressUseCase(snapshotStore)
    val probeSnapshotSpeed = ProbeSnapshotSpeedUseCase(HttpSnapshotSpeedProbe())
    val syncClient = SyncClientFromPanelUseCase(
        enrollment = enrollment,
        patchConfig = { format, template, assignments, iniSection, omitIniKeys ->
            HostClientConfigPatch.apply(format, template, assignments, iniSection, omitIniKeys)
        },
    )
    val nodeEvents = HttpPanelNodeEventsClient()
    val runningNodes = FileRunningNodeRegistry(dirs.configDir().resolve("running-nodes.json"))
    val bitcoreStarter = BitcoreNodeProcessStarter()
    val chainRuntimes = mapOf(
        "tron" to ChainNodeRuntime(
            network = "tron",
            starter = TronNodeProcessStarter(),
            height = TronNodeHeightProbe(),
        ),
        "zcash" to ChainNodeRuntime(
            network = "zcash",
            starter = ZcashNodeProcessStarter(),
            height = ZcashNodeHeightProbe(),
        ),
        "ethereum" to ChainNodeRuntime(
            network = "ethereum",
            starter = EthereumNodeProcessStarter(),
            height = EthereumNodeHeightProbe(),
        ),
        "solana" to ChainNodeRuntime(
            network = "solana",
            starter = SolanaNodeProcessStarter(),
            height = SolanaNodeHeightProbe(),
        ),
        "polygon" to ChainNodeRuntime(
            network = "polygon",
            starter = PolygonNodeProcessStarter(),
            height = PolygonNodeHeightProbe(),
        ),
        "bsc" to ChainNodeRuntime(
            network = "bsc",
            starter = BscNodeProcessStarter(),
            height = BscNodeHeightProbe(),
        ),
        "base" to ChainNodeRuntime(
            network = "base",
            starter = BaseNodeProcessStarter(),
            height = BaseNodeHeightProbe(),
        ),
        "arb" to ChainNodeRuntime(
            network = "arb",
            starter = ArbNodeProcessStarter(),
            height = ArbNodeHeightProbe(),
        ),
        "sui" to ChainNodeRuntime(
            network = "sui",
            starter = SuiNodeProcessStarter(),
            height = SuiNodeHeightProbe(),
        ),
        "hyperliquid" to ChainNodeRuntime(
            network = "hyperliquid",
            starter = HyperliquidNodeProcessStarter(),
            height = HyperliquidNodeHeightProbe(),
        ),
        "ton" to ChainNodeRuntime(
            network = "ton",
            starter = TonNodeProcessStarter(),
            height = TonNodeHeightProbe(),
        ),
        "xrpl" to ChainNodeRuntime(
            network = "xrpl",
            starter = XrplNodeProcessStarter(),
            height = XrplNodeHeightProbe(),
        ),
    ) + BitcoreChainSpecs.ALL.associate { spec ->
        spec.networkId.value to ChainNodeRuntime(
            network = spec.networkId.value,
            starter = bitcoreStarter,
            height = BitcoreNodeHeightProbe(spec),
        )
    }
    val startNode = StartNodeProcessUseCase(
        runtimes = chainRuntimes,
        registry = runningNodes,
        enrollment = enrollment,
        notifyStarted = nodeEvents,
        agentToken = cfg.token,
        scope = pushScope,
    )
    val clientUpdateState = rpcnode.toolkit.agent.application.client.ClientUpdateStateStore()
    val updateClient = rpcnode.toolkit.agent.application.client.UpdateClientOnHostUseCase(
        sync = syncClient,
        startNode = startNode,
        state = clientUpdateState,
        scope = pushScope,
        enrollment = enrollment,
        notifyPanel = nodeEvents,
        agentToken = cfg.token,
    )
    NodeHeightPusher(
        push = PushNodeHeightsUseCase(
            enrollment = enrollment,
            registry = runningNodes,
            runtimes = chainRuntimes,
            push = nodeEvents,
            token = cfg.token,
        ),
        scope = pushScope,
    ).start()
    log.info("log file {}", logFile)
    log.info("token file {}", cfg.tokenFile)
    log.info("HTTP access log → {}", logFile)
    if (!runningAsRoot())
    {
        log.warn(
            "agent is NOT running as root — node start via systemd will fail " +
                "(install with: sudo java -jar rpcnode-agent.jar install)",
        )
    }
    log.info("rpcnode-agent {} listening on {}:{}", cfg.version, cfg.listen, cfg.port)
    val displayHost = when (cfg.listen)
    {
        "0.0.0.0", "::", "" -> "127.0.0.1"
        else -> cfg.listen
    }
    println("Agent URL: http://$displayHost:${cfg.port}")
    println("Agent key: ${cfg.token}")
    try
    {
        embeddedServer(Netty, port = cfg.port, host = cfg.listen) {
            module(
                cfg,
                collectMetrics,
                enrollPanel,
                unenrollPanel,
                updateAgent,
                CheckPortsUseCase(TcpPortProbe()),
                GetHostDisksUseCase(LsblkHostDiskProbe()),
                GetHostSysctlUseCase(SolanaHostSysctlProbe()),
                startSnapshot,
                getSnapshotProgress,
                probeSnapshotSpeed,
                syncClient = syncClient,
                updateClient = updateClient,
                startNode = startNode,
                getNodeLogs = GetNodeProcessLogsUseCase(runningNodes),
                getNodeClientVersion = GetNodeClientVersionUseCase(runningNodes, enrollment),
                controlNodeUnit = ControlNodeUnitUseCase(runningNodes),
                removeNodeHost = RemoveNodeHostUseCase(runningNodes),
            )
        }.start(wait = true)
    }
    catch (e: Exception)
    {
        if (isAddressInUse(e))
        {
            failListenPort(log, listenPortBusyMessage(cfg.listen, cfg.port)
                ?: "cannot bind ${cfg.listen}:${cfg.port} — port in use")
        }
        log.error("failed to listen on {}:{}", cfg.listen, cfg.port, e)
        kotlin.system.exitProcess(1)
    }
}

private fun failListenPort(log: Logger, message: String): Nothing
{
    System.err.println(message)
    System.err.println("stop the process on that port or: sudo systemctl stop rpcnode-agent")
    log.error(message)
    kotlin.system.exitProcess(1)
}

fun Application.module(
    cfg: AgentConfig,
    collectMetrics: CollectHostMetricsUseCase = CollectHostMetricsUseCase(LinuxHostMetrics()),
    enrollPanel: EnrollPanelUseCase = EnrollPanelUseCase(
        InMemoryPanelEnrollmentStore(),
        ProbePanel { true },
    ),
    unenrollPanel: UnenrollPanelUseCase = UnenrollPanelUseCase(InMemoryPanelEnrollmentStore()),
    updateAgent: UpdateAgentUseCase = UpdateAgentUseCase(
        localVersion = cfg.version,
        resolvePanelUrl = { null },
        channel = AgentReleaseChannel { null },
        installer = AgentJarInstaller { AgentInstallResult.Failed("not wired") },
        restarter = AgentRestarter { },
    ),
    checkPorts: CheckPortsUseCase = CheckPortsUseCase(TcpPortProbe()),
    getHostDisks: GetHostDisksUseCase = GetHostDisksUseCase(LsblkHostDiskProbe()),
    getHostSysctl: GetHostSysctlUseCase = GetHostSysctlUseCase(SolanaHostSysctlProbe()),
    startSnapshot: StartSnapshotDownloadUseCase = StartSnapshotDownloadUseCase(
        store = FileSnapshotJobStore(Path.of("/tmp/rpcnode-agent/snapshots")),
        downloadRoot = Path.of("/tmp/rpcnode-agent/snapshot-downloads"),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ),
    snapshotProgress: GetSnapshotProgressUseCase = GetSnapshotProgressUseCase(
        FileSnapshotJobStore(Path.of("/tmp/rpcnode-agent/snapshots")),
    ),
    probeSnapshotSpeed: ProbeSnapshotSpeedUseCase = ProbeSnapshotSpeedUseCase(HttpSnapshotSpeedProbe()),
    syncClient: SyncClientFromPanelUseCase? = null,
    updateClient: UpdateClientOnHostUseCase? = null,
    startNode: StartNodeProcessUseCase? = null,
    getNodeLogs: GetNodeProcessLogsUseCase? = null,
    getNodeClientVersion: GetNodeClientVersionUseCase? = null,
    controlNodeUnit: ControlNodeUnitUseCase? = null,
    removeNodeHost: RemoveNodeHostUseCase? = null,
)
{
    installHttpCallLogging()
    install(ContentNegotiation) {
        json(
            Json {
                encodeDefaults = true
                prettyPrint = false
                ignoreUnknownKeys = true
            },
        )
    }
    agentApiRoutes(
        cfg,
        GetAgentIdentityUseCase(version = cfg.version, port = cfg.port),
        collectMetrics,
        enrollPanel,
        unenrollPanel,
        updateAgent,
        checkPorts,
        getHostDisks,
        getHostSysctl,
        startSnapshot,
        snapshotProgress,
        probeSnapshotSpeed,
        syncClient = syncClient,
        updateClient = updateClient,
        startNode = startNode,
        getNodeLogs = getNodeLogs,
        getNodeClientVersion = getNodeClientVersion,
        controlNodeUnit = controlNodeUnit,
        removeNodeHost = removeNodeHost,
    )
}
