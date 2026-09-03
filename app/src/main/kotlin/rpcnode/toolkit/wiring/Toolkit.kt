package rpcnode.toolkit.wiring

import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Semaphore
import rpcnode.toolkit.catalog.application.LookupNetworkEnvUseCase
import rpcnode.toolkit.catalog.application.LookupNetworkUseCase
import rpcnode.toolkit.catalog.domain.NetworkCatalog
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.arb.infrastructure.docker.ArbAwareArtifactDownloader
import rpcnode.toolkit.chains.arb.infrastructure.http.ArbClientReleaseResolver
import rpcnode.toolkit.chains.arb.infrastructure.http.ArbNetworkTipProbe
import rpcnode.toolkit.chains.arb.infrastructure.start.ArbNodeStart
import rpcnode.toolkit.chains.bitcore.infrastructure.BitcoreChainWiring
import rpcnode.toolkit.chains.bitcore.infrastructure.BlockchairStatsTipProbe
import rpcnode.toolkit.chains.base.infrastructure.http.BaseClientReleaseResolver
import rpcnode.toolkit.chains.base.infrastructure.http.BaseNetworkTipProbe
import rpcnode.toolkit.chains.base.infrastructure.http.BaseSnapshotResolver
import rpcnode.toolkit.chains.base.infrastructure.http.HttpBaseSnapshotTipProbe
import rpcnode.toolkit.chains.base.infrastructure.start.BaseNodeStart
import rpcnode.toolkit.chains.bsc.infrastructure.http.BscClientReleaseResolver
import rpcnode.toolkit.chains.bsc.infrastructure.http.BscNetworkTipProbe
import rpcnode.toolkit.chains.bsc.infrastructure.http.BscSnapshotResolver
import rpcnode.toolkit.chains.bsc.infrastructure.start.BscNodeStart
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumClientReleaseResolver
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumGethArtifactUrlResolver
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumNetworkTipProbe
import rpcnode.toolkit.chains.ethereum.infrastructure.start.EthereumNodeStart
import rpcnode.toolkit.chains.polygon.infrastructure.http.PolygonClientReleaseResolver
import rpcnode.toolkit.chains.polygon.infrastructure.http.PolygonNetworkTipProbe
import rpcnode.toolkit.chains.polygon.infrastructure.start.PolygonNodeStart
import rpcnode.toolkit.chains.solana.infrastructure.http.SolanaClientReleaseResolver
import rpcnode.toolkit.chains.solana.infrastructure.http.SolanaNetworkTipProbe
import rpcnode.toolkit.chains.solana.infrastructure.start.SolanaNodeStart
import rpcnode.toolkit.chains.sui.infrastructure.http.SuiClientReleaseResolver
import rpcnode.toolkit.chains.sui.infrastructure.http.SuiNetworkTipProbe
import rpcnode.toolkit.chains.sui.infrastructure.http.SuiSnapshotResolver
import rpcnode.toolkit.chains.sui.infrastructure.start.SuiNodeStart
import rpcnode.toolkit.chains.xrpl.infrastructure.http.XrplClientReleaseResolver
import rpcnode.toolkit.chains.xrpl.infrastructure.http.XrplNetworkTipProbe
import rpcnode.toolkit.chains.xrpl.infrastructure.start.XrplNodeStart
import rpcnode.toolkit.chains.hyperliquid.infrastructure.http.HyperliquidClientReleaseResolver
import rpcnode.toolkit.chains.hyperliquid.infrastructure.http.HyperliquidNetworkTipProbe
import rpcnode.toolkit.chains.hyperliquid.infrastructure.start.HyperliquidNodeStart
import rpcnode.toolkit.chains.ton.infrastructure.http.TonNetworkTipProbe
import rpcnode.toolkit.chains.ton.infrastructure.start.TonNodeStart
import rpcnode.toolkit.chains.tron.infrastructure.http.TronClientReleaseResolver
import rpcnode.toolkit.chains.tron.infrastructure.http.TronNetworkTipProbe
import rpcnode.toolkit.chains.tron.infrastructure.http.TronSnapshotResolver
import rpcnode.toolkit.chains.tron.infrastructure.start.TronNodeStart
import rpcnode.toolkit.chains.zcash.infrastructure.http.ZcashClientReleaseResolver
import rpcnode.toolkit.chains.zcash.infrastructure.start.ZcashNodeStart
import rpcnode.toolkit.networks.application.connect.ListEthereumNodesUseCase
import rpcnode.toolkit.networks.application.connect.ListL1ParentChoicesUseCase
import rpcnode.toolkit.networks.application.tip.NetworkTipCache
import rpcnode.toolkit.networks.application.tip.NetworkTipProbeRegistry
import rpcnode.toolkit.nodes.application.height.GetNodeHeightUseCase
import rpcnode.toolkit.nodes.application.logs.GetNodeLogsUseCase
import rpcnode.toolkit.nodes.application.process.ControlNodeProcessUseCase
import rpcnode.toolkit.nodes.application.version.GetNodeClientVersionUseCase
import rpcnode.toolkit.nodes.infrastructure.http.HttpNodeClientVersionHostClient
import rpcnode.toolkit.nodes.infrastructure.http.HttpNodeLogsHostClient
import rpcnode.toolkit.nodes.infrastructure.http.HttpNodeProcessControlClient
import rpcnode.toolkit.nodes.infrastructure.http.HttpRemoveNodeOnHost
import rpcnode.toolkit.clients.application.ClientDownloadTracker
import rpcnode.toolkit.clients.application.ClientPreviewStore
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.GitHubTokenProvider
import rpcnode.toolkit.clients.application.add.AddClientUseCase
import rpcnode.toolkit.clients.application.delete.DeleteClientUseCase
import rpcnode.toolkit.clients.application.downloadone.DownloadClientProgramUseCase
import rpcnode.toolkit.clients.application.list.ListClientsUseCase
import rpcnode.toolkit.clients.application.preview.PreviewClientsUseCase
import rpcnode.toolkit.clients.application.probe.ProbeClientsUseCase
import rpcnode.toolkit.clients.application.probeone.ProbeClientProgramUseCase
import rpcnode.toolkit.clients.application.sync.SyncClientsUseCase
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.application.version.ResolveClientReleaseUseCase
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog
import rpcnode.toolkit.clients.infrastructure.filesystem.FileClientManifestWriter
import rpcnode.toolkit.clients.infrastructure.filesystem.FileInstallPlanWriter
import rpcnode.toolkit.clients.infrastructure.github.HttpGitHubReleaseClient
import rpcnode.toolkit.clients.infrastructure.http.HttpArtifactDownloader
import rpcnode.toolkit.clients.infrastructure.persistence.SqliteClientVersionRepository
import rpcnode.toolkit.clients.infrastructure.settings.SettingsBackedGitHubTokenProvider
import rpcnode.toolkit.clients.infrastructure.tracking.InMemoryClientDownloadTracker
import rpcnode.toolkit.clients.infrastructure.tracking.InMemoryClientPreviewStore
import rpcnode.toolkit.networks.application.ClientFilesReadyChecker
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.application.install.CheckNetworkInstallUseCase
import rpcnode.toolkit.networks.application.list.ListNetworksUseCase
import rpcnode.toolkit.networks.application.remove.RemoveNetworkUseCase
import rpcnode.toolkit.networks.application.setstatus.SetNetworkStatusUseCase
import rpcnode.toolkit.networks.application.snapshot.CdnMirrorProbe
import rpcnode.toolkit.networks.application.snapshot.ListSnapshotSourcesUseCase
import rpcnode.toolkit.networks.application.snapshot.PreferCdnSnapshotUseCase
import rpcnode.toolkit.networks.application.snapshot.ResolveSnapshotUseCase
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.networks.domain.repository.NetworkRepository
import rpcnode.toolkit.networks.infrastructure.catalog.YamlSnapshotMirrorCatalog
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.networks.infrastructure.filesystem.DiskClientFilesReadyChecker
import rpcnode.toolkit.networks.infrastructure.http.HttpCdnMirrorProbe
import rpcnode.toolkit.networks.infrastructure.persistence.SqliteNetworkRepository
import rpcnode.toolkit.nodes.application.add.AddNodeUseCase
import rpcnode.toolkit.nodes.application.get.GetNodeUseCase
import rpcnode.toolkit.nodes.application.list.ListNodesUseCase
import rpcnode.toolkit.nodes.application.options.SaveNodeInstallOptionsUseCase
import rpcnode.toolkit.nodes.application.config.ApplyNodeClientConfigUseCase
import rpcnode.toolkit.nodes.application.ingest.IngestNodeHeightsUseCase
import rpcnode.toolkit.nodes.application.ingest.MarkNodeStartedUseCase
import rpcnode.toolkit.nodes.application.start.StartNodeUseCase
import rpcnode.toolkit.nodes.application.update.ClientUpdateProgressStore
import rpcnode.toolkit.nodes.application.update.GetNodeClientUpdateUseCase
import rpcnode.toolkit.nodes.application.update.IngestClientUpdateProgressUseCase
import rpcnode.toolkit.nodes.application.update.RollbackNodeClientUseCase
import rpcnode.toolkit.nodes.application.update.UpdateNodeClientUseCase
import rpcnode.toolkit.nodes.infrastructure.http.HttpStartNodeOnHost
import rpcnode.toolkit.nodes.infrastructure.http.HttpSyncClientOnHost
import rpcnode.toolkit.nodes.infrastructure.http.HttpUpdateClientOnHost
import rpcnode.toolkit.nodes.application.ports.CheckHostPortsUseCase
import rpcnode.toolkit.nodes.application.ports.GetNodePortsUseCase
import rpcnode.toolkit.nodes.application.disks.GetHostDisksUseCase
import rpcnode.toolkit.nodes.application.disks.GetNodeDiskLayoutUseCase
import rpcnode.toolkit.nodes.application.disks.SaveNodeDiskLayoutUseCase
import rpcnode.toolkit.nodes.application.sysctl.GetHostSysctlUseCase
import rpcnode.toolkit.nodes.application.snapshot.GetNodeSnapshotPlanUseCase
import rpcnode.toolkit.nodes.application.snapshot.ResolveSnapshotDestDirUseCase
import rpcnode.toolkit.nodes.application.snapshot.GetNodeSnapshotProgressUseCase
import rpcnode.toolkit.nodes.application.snapshot.ProbeNodeSnapshotSourcesUseCase
import rpcnode.toolkit.nodes.application.snapshot.StartNodeSnapshotUseCase
import rpcnode.toolkit.nodes.application.snapshot.StopNodeSnapshotUseCase
import rpcnode.toolkit.nodes.application.status.UpdateNodeStatusUseCase
import rpcnode.toolkit.nodes.application.remove.RemoveNodeUseCase
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.nodes.infrastructure.http.HttpHostDiskReader
import rpcnode.toolkit.nodes.infrastructure.http.HttpHostSysctlReader
import rpcnode.toolkit.nodes.infrastructure.http.HttpSnapshotHostClient
import rpcnode.toolkit.nodes.infrastructure.persistence.SqliteNodeRepository
import rpcnode.toolkit.auth.application.login.LoginUseCase
import rpcnode.toolkit.auth.application.logout.LogoutUseCase
import rpcnode.toolkit.auth.application.status.GetAuthStatusUseCase
import rpcnode.toolkit.auth.domain.repository.CredentialStore
import rpcnode.toolkit.auth.domain.repository.SessionStore
import rpcnode.toolkit.auth.infrastructure.persistence.HtpasswdCredentialStore
import rpcnode.toolkit.auth.infrastructure.persistence.SqliteSessionStore
import rpcnode.toolkit.panel.presentation.http.ServerConfig
import rpcnode.toolkit.servers.application.ingest.IngestServerMetricsUseCase
import rpcnode.toolkit.servers.application.list.ListServersUseCase
import rpcnode.toolkit.servers.application.origin.ResolvePanelOriginUseCase
import rpcnode.toolkit.servers.application.register.RegisterServerUseCase
import rpcnode.toolkit.servers.application.remove.FinishRemoveServerUseCase
import rpcnode.toolkit.servers.application.remove.RemoveServerUseCase
import rpcnode.toolkit.servers.application.remove.ResumeServerRemovalsUseCase
import rpcnode.toolkit.servers.application.update.UpdateHostAgentUseCase
import rpcnode.toolkit.servers.application.update.UpdateServerUseCase
import rpcnode.toolkit.servers.domain.repository.ServerRepository
import rpcnode.toolkit.servers.infrastructure.http.HttpHostAgentClient
import rpcnode.toolkit.servers.infrastructure.persistence.SqliteServerMetricsRepository
import rpcnode.toolkit.servers.infrastructure.persistence.SqliteServerRepository
import rpcnode.toolkit.settings.application.get.GetSettingsUseCase
import rpcnode.toolkit.settings.application.get.UrlProbe
import rpcnode.toolkit.settings.application.save.GitHubTokenChecker
import rpcnode.toolkit.settings.application.save.SaveSettingsUseCase
import rpcnode.toolkit.settings.domain.repository.SettingsStore
import rpcnode.toolkit.settings.infrastructure.http.HttpGitHubTokenChecker
import rpcnode.toolkit.settings.infrastructure.http.HttpUrlProbe
import rpcnode.toolkit.settings.infrastructure.persistence.AesGcmSecretBox
import rpcnode.toolkit.settings.infrastructure.persistence.DiskInstallFiles
import rpcnode.toolkit.settings.infrastructure.persistence.FileInstallStampReader
import rpcnode.toolkit.settings.infrastructure.persistence.SqliteSettingsStore
import rpcnode.toolkit.notifications.application.ClearTelegramBotUseCase
import rpcnode.toolkit.notifications.application.ConfigureTelegramBotUseCase
import rpcnode.toolkit.notifications.application.DiscoverTelegramChatsUseCase
import rpcnode.toolkit.notifications.application.GetTelegramNotificationSettingsUseCase
import rpcnode.toolkit.notifications.application.NotifyClientUpdatesUseCase
import rpcnode.toolkit.notifications.application.SelectTelegramChatUseCase
import rpcnode.toolkit.notifications.application.SendTelegramTestUseCase
import rpcnode.toolkit.notifications.application.SetTelegramNotificationsEnabledUseCase
import rpcnode.toolkit.notifications.infrastructure.http.HttpTelegramBotApi
import rpcnode.toolkit.notifications.infrastructure.persistence.SqliteNotificationSettingsStore
import rpcnode.toolkit.setup.application.create.CreateAdminUseCase
import rpcnode.toolkit.setup.application.status.GetSetupStatusUseCase
import rpcnode.toolkit.shared.infrastructure.persistence.ToolkitDatabase

class Toolkit(
    // Networks
    val catalog: NetworkCatalog,
    val networkFacts: NetworkFactsRepository,
    val lookupNetwork: LookupNetworkUseCase,
    val lookupNetworkEnv: LookupNetworkEnvUseCase,
    val listNetworks: ListNetworksUseCase,
    val setNetworkStatus: SetNetworkStatusUseCase,
    val removeNetwork: RemoveNetworkUseCase,
    val checkNetworkInstall: CheckNetworkInstallUseCase,
    val resolveSnapshot: ResolveSnapshotUseCase,
    val listSnapshotSources: ListSnapshotSourcesUseCase,
    val preferCdnSnapshot: PreferCdnSnapshotUseCase,
    val listEthereumNodes: ListEthereumNodesUseCase,
    val listL1ParentChoices: ListL1ParentChoicesUseCase,

    // Auth
    val credentials: CredentialStore,
    val sessions: SessionStore,
    val getAuthStatus: GetAuthStatusUseCase,
    val getSetupStatus: GetSetupStatusUseCase,
    val createAdmin: CreateAdminUseCase,
    val login: LoginUseCase,
    val logout: LogoutUseCase,

    // Settings
    val getSettings: GetSettingsUseCase,
    val saveSettings: SaveSettingsUseCase,

    // Notifications
    val getTelegramNotificationSettings: GetTelegramNotificationSettingsUseCase,
    val configureTelegramBot: ConfigureTelegramBotUseCase,
    val discoverTelegramChats: DiscoverTelegramChatsUseCase,
    val selectTelegramChat: SelectTelegramChatUseCase,
    val setTelegramNotificationsEnabled: SetTelegramNotificationsEnabledUseCase,
    val clearTelegramBot: ClearTelegramBotUseCase,
    val sendTelegramTest: SendTelegramTestUseCase,
    val notifyClientUpdates: NotifyClientUpdatesUseCase,

    // Clients
    val clientProgramCatalog: ClientProgramCatalog,
    val listClients: ListClientsUseCase,
    val previewClients: PreviewClientsUseCase,
    val addClient: AddClientUseCase,
    val probeClients: ProbeClientsUseCase,
    val syncClients: SyncClientsUseCase,
    val deleteClient: DeleteClientUseCase,
    val resolveClientRelease: ResolveClientReleaseUseCase,
    val githubTokenProvider: GitHubTokenProvider,
    val clientsDestDir: Path,

    // Servers / nodes
    val listServers: ListServersUseCase,
    val registerServer: RegisterServerUseCase,
    val updateServer: UpdateServerUseCase,
    val ingestServerMetrics: IngestServerMetricsUseCase,
    val resolvePanelOrigin: ResolvePanelOriginUseCase,
    val removeServer: RemoveServerUseCase,
    val resumeServerRemovals: ResumeServerRemovalsUseCase,
    val updateHostAgent: UpdateHostAgentUseCase,
    val listNodes: ListNodesUseCase,
    val getNode: GetNodeUseCase,
    val addNode: AddNodeUseCase,
    val removeNode: RemoveNodeUseCase,
    val getNodePorts: GetNodePortsUseCase,
    val checkHostPorts: CheckHostPortsUseCase,
    val getHostDisks: GetHostDisksUseCase,
    val getHostSysctl: GetHostSysctlUseCase,
    val getNodeDiskLayout: GetNodeDiskLayoutUseCase,
    val saveNodeDiskLayout: SaveNodeDiskLayoutUseCase,
    val saveNodeInstallOptions: SaveNodeInstallOptionsUseCase,
    val applyNodeClientConfig: ApplyNodeClientConfigUseCase,
    val startNode: StartNodeUseCase,
    val updateNodeClient: UpdateNodeClientUseCase,
    val getNodeClientUpdate: GetNodeClientUpdateUseCase,
    val rollbackNodeClient: RollbackNodeClientUseCase,
    val getNodeHeight: GetNodeHeightUseCase,
    val getNodeLogs: GetNodeLogsUseCase,
    val getNodeClientVersion: GetNodeClientVersionUseCase,
    val controlNodeProcess: ControlNodeProcessUseCase,
    val markNodeStarted: MarkNodeStartedUseCase,
    val ingestNodeHeights: IngestNodeHeightsUseCase,
    val ingestClientUpdateProgress: IngestClientUpdateProgressUseCase,
    val clientUpdateProgress: ClientUpdateProgressStore,
    val updateNodeStatus: UpdateNodeStatusUseCase,
    val getNodeSnapshotPlan: GetNodeSnapshotPlanUseCase,
    val probeNodeSnapshotSources: ProbeNodeSnapshotSourcesUseCase,
    val startNodeSnapshot: StartNodeSnapshotUseCase,
    val stopNodeSnapshot: StopNodeSnapshotUseCase,
    val getNodeSnapshotProgress: GetNodeSnapshotProgressUseCase,
)
{
    companion object
    {
        fun production(
            cfg: ServerConfig = ServerConfig(),
            githubChecker: GitHubTokenChecker = HttpGitHubTokenChecker(),
            urlProbe: UrlProbe = HttpUrlProbe(),
        ): Toolkit
        {
            val mapping = YamlNetworkFactsRepository()
            val catalog: NetworkCatalog = mapping
            val db = ToolkitDatabase(Path.of(cfg.dbPath))
            val dataDir = Path.of(cfg.dbPath).toAbsolutePath().parent ?: Path.of("database")

            // Auth
            val credentials = HtpasswdCredentialStore(Path.of(cfg.htpasswdPath))
            val sessions = SqliteSessionStore(db)

            // Settings
            val settingsStore: SettingsStore = SqliteSettingsStore(
                db = db,
                githubTokenFile = dataDir.resolve("github-token"),
                secrets = AesGcmSecretBox(
                    keyFile = dataDir.resolve("panel.notify.key"),
                    envKeyBase64 = cfg.notifyKey,
                ),
            )
            val getSettings = GetSettingsUseCase(
                store = settingsStore,
                probe = urlProbe,
                installFiles = DiskInstallFiles(Path.of(cfg.installDir)),
                envOrigin = cfg.installOriginOverride,
                envSnapshotCdnOrigin = cfg.snapshotCdnOriginOverride,
                panelVersion = cfg.panelVersion,
                installStamp = FileInstallStampReader(dataDir.resolve("panel.install")),
            )
            val notificationSettingsStore = SqliteNotificationSettingsStore(
                db = db,
                secrets = AesGcmSecretBox(
                    keyFile = dataDir.resolve("panel.notify.key"),
                    envKeyBase64 = cfg.notifyKey,
                ),
            )
            val telegramBotApi = HttpTelegramBotApi()

            // Networks
            val networkRepository: NetworkRepository = SqliteNetworkRepository(db)
            val clientFilesReady: ClientFilesReadyChecker = DiskClientFilesReadyChecker(Path.of(cfg.clientsDestDir))
            val networkFacts: NetworkFactsRepository = mapping
            val snapshotMirrors = YamlSnapshotMirrorCatalog()
            val snapshotResolvers: Map<NetworkId, SnapshotResolver> = mapOf(
                NetworkId.TRON to TronSnapshotResolver(mirrors = snapshotMirrors),
                NetworkId.BSC to BscSnapshotResolver(),
                NetworkId.BASE to BaseSnapshotResolver(),
                NetworkId.SUI to SuiSnapshotResolver(),
            )
            val resolveSnapshot = ResolveSnapshotUseCase(catalog, snapshotResolvers)
            val cdnMirrorProbe: CdnMirrorProbe = HttpCdnMirrorProbe()
            val listSnapshotSources = ListSnapshotSourcesUseCase(
                resolve = resolveSnapshot,
                store = settingsStore,
                probe = cdnMirrorProbe,
                envSnapshotCdnOrigin = cfg.snapshotCdnOriginOverride,
                baseTip = HttpBaseSnapshotTipProbe(),
            )
            val preferCdnSnapshot = PreferCdnSnapshotUseCase(listSnapshotSources)

            // No separate worker process: probe/sync fire coroutines directly from use cases into
            // this app-wide scope, bounded by a shared semaphore so "update all" can't open
            // unbounded parallel HTTP connections.
            val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val backgroundConcurrency = Semaphore(4)

            // Clients
            val clientVersionRepository: ClientVersionRepository = SqliteClientVersionRepository(db)
            val clientProgramCatalog: ClientProgramCatalog = YamlClientProgramCatalog()
            val githubTokenProvider: GitHubTokenProvider = SettingsBackedGitHubTokenProvider(settingsStore)
            val githubReleaseClient: GitHubReleaseClient = HttpGitHubReleaseClient(githubTokenProvider)
            val blockchairTip = BlockchairStatsTipProbe()
            val clientReleaseResolvers: Map<NetworkId, ClientReleaseResolver> =
                BitcoreChainWiring.clientReleaseResolvers(githubReleaseClient) +
                    mapOf(
                        NetworkId.TRON to TronClientReleaseResolver(githubReleaseClient),
                        NetworkId.ZCASH to ZcashClientReleaseResolver(githubReleaseClient),
                        NetworkId.ETHEREUM to EthereumClientReleaseResolver(githubReleaseClient),
                        NetworkId.SOLANA to SolanaClientReleaseResolver(githubReleaseClient),
                        NetworkId.POLYGON to PolygonClientReleaseResolver(githubReleaseClient),
                        NetworkId.BSC to BscClientReleaseResolver(githubReleaseClient),
                        NetworkId.BASE to BaseClientReleaseResolver(githubReleaseClient),
                        NetworkId.ARB to ArbClientReleaseResolver(githubReleaseClient),
                        NetworkId.SUI to SuiClientReleaseResolver(githubReleaseClient),
                        NetworkId.HYPERLIQUID to HyperliquidClientReleaseResolver(),
                        NetworkId.XRPL to XrplClientReleaseResolver(githubReleaseClient),
                    )
            val artifactDownloader = ArbAwareArtifactDownloader(HttpArtifactDownloader(githubTokenProvider))
            val clientDownloadTracker: ClientDownloadTracker = InMemoryClientDownloadTracker()
            val clientPreviewStore: ClientPreviewStore = InMemoryClientPreviewStore()
            val probeClientProgram = ProbeClientProgramUseCase(
                versionRepository = clientVersionRepository,
                githubReleaseClient = githubReleaseClient,
                previewStore = clientPreviewStore,
                clientReleaseResolvers = clientReleaseResolvers,
            )
            val probeClients = ProbeClientsUseCase(
                versionRepository = clientVersionRepository,
                programCatalog = clientProgramCatalog,
                probeOne = probeClientProgram,
                tokenProvider = githubTokenProvider,
                concurrency = backgroundConcurrency,
            )
            val notifyClientUpdates = NotifyClientUpdatesUseCase(
                probeClients = probeClients,
                clients = clientVersionRepository,
                settings = notificationSettingsStore,
                telegram = telegramBotApi,
            )
            val downloadClientProgram = DownloadClientProgramUseCase(
                versionRepository = clientVersionRepository,
                githubReleaseClient = githubReleaseClient,
                artifactDownloader = artifactDownloader,
                tracker = clientDownloadTracker,
                manifestWriter = FileClientManifestWriter(),
                installPlanWriter = FileInstallPlanWriter(),
                destDir = Path.of(cfg.clientsDestDir),
                clientReleaseResolvers = clientReleaseResolvers,
                artifactUrlResolvers = mapOf(
                    NetworkId.ETHEREUM to EthereumGethArtifactUrlResolver(),
                ),
            )

            val serverRepository: ServerRepository = SqliteServerRepository(db)
            val serverMetricsRepository = SqliteServerMetricsRepository(db)
            val hostAgent = HttpHostAgentClient()
            val snapshotHost = HttpSnapshotHostClient()
            val hostDisks = GetHostDisksUseCase(
                servers = serverRepository,
                reader = HttpHostDiskReader(),
            )
            val hostSysctl = GetHostSysctlUseCase(
                servers = serverRepository,
                reader = HttpHostSysctlReader(),
            )
            val nodeRepository: NodeRepository = SqliteNodeRepository(db)
            val finishRemoveServer = FinishRemoveServerUseCase(serverRepository, hostAgent)
            val removeServer = RemoveServerUseCase(
                servers = serverRepository,
                nodes = nodeRepository,
                finish = finishRemoveServer,
                backgroundScope = backgroundScope,
            )
            val getNodeDiskLayout = GetNodeDiskLayoutUseCase(
                nodes = nodeRepository,
                facts = networkFacts,
                hostDisks = hostDisks,
            )
            val resolveSnapshotDestDir = ResolveSnapshotDestDirUseCase(getNodeDiskLayout, networkFacts)
            val saveNodeInstallOptions = SaveNodeInstallOptionsUseCase(
                nodes = nodeRepository,
                facts = networkFacts,
            )
            val applyNodeClientConfig = ApplyNodeClientConfigUseCase(
                nodes = nodeRepository,
                servers = serverRepository,
                facts = networkFacts,
                catalog = clientProgramCatalog,
                saveInstallOptions = saveNodeInstallOptions,
                resolveDestDir = resolveSnapshotDestDir,
                syncOnHost = HttpSyncClientOnHost(),
                installPlanWriter = FileInstallPlanWriter(),
                clientsDestDir = Path.of(cfg.clientsDestDir),
                downloadClient = downloadClientProgram,
            )
            val tipProbes = NetworkTipProbeRegistry(
                BitcoreChainWiring.tipProbes(blockchairTip) +
                    mapOf(
                        NetworkId.TRON to TronNetworkTipProbe(),
                        NetworkId.ZCASH to blockchairTip,
                        NetworkId.ETHEREUM to EthereumNetworkTipProbe(),
                        NetworkId.SOLANA to SolanaNetworkTipProbe(),
                        NetworkId.POLYGON to PolygonNetworkTipProbe(),
                        NetworkId.BSC to BscNetworkTipProbe(),
                        NetworkId.BASE to BaseNetworkTipProbe(),
                        NetworkId.ARB to ArbNetworkTipProbe(),
                        NetworkId.SUI to SuiNetworkTipProbe(),
                        NetworkId.HYPERLIQUID to HyperliquidNetworkTipProbe(),
                        NetworkId.TON to TonNetworkTipProbe(),
                        NetworkId.XRPL to XrplNetworkTipProbe(),
                    ),
            )
            val tipCache = NetworkTipCache(networkFacts, tipProbes)
            val chainStartsMap = BitcoreChainWiring.chainStarts() +
                mapOf(
                    NetworkId.TRON to TronNodeStart(),
                    NetworkId.ZCASH to ZcashNodeStart(),
                    NetworkId.ETHEREUM to EthereumNodeStart(),
                    NetworkId.SOLANA to SolanaNodeStart(),
                    NetworkId.POLYGON to PolygonNodeStart(),
                    NetworkId.BSC to BscNodeStart(),
                    NetworkId.BASE to BaseNodeStart(),
                    NetworkId.ARB to ArbNodeStart(),
                    NetworkId.SUI to SuiNodeStart(),
                    NetworkId.HYPERLIQUID to HyperliquidNodeStart(),
                    NetworkId.TON to TonNodeStart(),
                    NetworkId.XRPL to XrplNodeStart(),
                )
            val startNode = StartNodeUseCase(
                saveInstallOptions = saveNodeInstallOptions,
                nodes = nodeRepository,
                servers = serverRepository,
                facts = networkFacts,
                catalog = clientProgramCatalog,
                clients = clientVersionRepository,
                resolveDestDir = resolveSnapshotDestDir,
                startOnHost = HttpStartNodeOnHost(),
                chainStarts = chainStartsMap,
            )
            val updateClientOnHost = HttpUpdateClientOnHost()
            val clientUpdateProgress = ClientUpdateProgressStore()
            val updateNodeClient = UpdateNodeClientUseCase(
                nodes = nodeRepository,
                servers = serverRepository,
                facts = networkFacts,
                catalog = clientProgramCatalog,
                clients = clientVersionRepository,
                resolveDestDir = resolveSnapshotDestDir,
                updateOnHost = updateClientOnHost,
                installPlanWriter = FileInstallPlanWriter(),
                clientsDestDir = Path.of(cfg.clientsDestDir),
                downloadClient = downloadClientProgram,
                chainStarts = chainStartsMap,
                progress = clientUpdateProgress,
            )
            val getNodeClientUpdate = GetNodeClientUpdateUseCase(
                nodes = nodeRepository,
                servers = serverRepository,
                updateOnHost = updateClientOnHost,
                progress = clientUpdateProgress,
                clients = clientVersionRepository,
                facts = networkFacts,
            )
            val rollbackNodeClient = RollbackNodeClientUseCase(
                nodes = nodeRepository,
                servers = serverRepository,
                updateOnHost = updateClientOnHost,
            )
            val getNodeHeight = GetNodeHeightUseCase(
                nodes = nodeRepository,
                tipCache = tipCache,
            )
            val getNodeLogs = GetNodeLogsUseCase(
                nodes = nodeRepository,
                servers = serverRepository,
                facts = networkFacts,
                catalog = clientProgramCatalog,
                resolveDestDir = resolveSnapshotDestDir,
                fetchOnHost = HttpNodeLogsHostClient(),
            )
            val getNodeClientVersion = GetNodeClientVersionUseCase(
                nodes = nodeRepository,
                servers = serverRepository,
                facts = networkFacts,
                clients = clientVersionRepository,
                resolveDestDir = resolveSnapshotDestDir,
                fetchOnHost = HttpNodeClientVersionHostClient(),
            )
            val controlNodeProcess = ControlNodeProcessUseCase(
                nodes = nodeRepository,
                servers = serverRepository,
                controlOnHost = HttpNodeProcessControlClient(),
                startNode = startNode,
            )
            val startNodeSnapshot = StartNodeSnapshotUseCase(
                nodes = nodeRepository,
                servers = serverRepository,
                facts = networkFacts,
                saveInstallOptions = saveNodeInstallOptions,
                preferSnapshot = preferCdnSnapshot,
                resolveDestDir = resolveSnapshotDestDir,
                startOnHost = snapshotHost,
            )

            return Toolkit(
                catalog = catalog,
                networkFacts = networkFacts,
                lookupNetwork = LookupNetworkUseCase(catalog),
                lookupNetworkEnv = LookupNetworkEnvUseCase(catalog),
                listNetworks = ListNetworksUseCase(
                    catalog,
                    networkRepository,
                    clientFilesReady,
                    networkFacts,
                ),
                setNetworkStatus = SetNetworkStatusUseCase(catalog, networkRepository),
                removeNetwork = RemoveNetworkUseCase(networkRepository),
                checkNetworkInstall = CheckNetworkInstallUseCase(catalog, clientFilesReady),
                resolveSnapshot = resolveSnapshot,
                listSnapshotSources = listSnapshotSources,
                preferCdnSnapshot = preferCdnSnapshot,
                listEthereumNodes = ListEthereumNodesUseCase(
                    facts = networkFacts,
                    nodes = nodeRepository,
                    servers = serverRepository,
                ),
                listL1ParentChoices = ListL1ParentChoicesUseCase(
                    facts = networkFacts,
                    nodes = nodeRepository,
                    servers = serverRepository,
                ),

                credentials = credentials,
                sessions = sessions,
                getAuthStatus = GetAuthStatusUseCase(sessions),
                getSetupStatus = GetSetupStatusUseCase(credentials),
                createAdmin = CreateAdminUseCase(credentials, sessions),
                login = LoginUseCase(credentials, sessions),
                logout = LogoutUseCase(sessions),

                getSettings = getSettings,
                saveSettings = SaveSettingsUseCase(
                    store = settingsStore,
                    checker = githubChecker,
                    getSettings = getSettings,
                    probe = urlProbe,
                ),

                getTelegramNotificationSettings = GetTelegramNotificationSettingsUseCase(notificationSettingsStore),
                configureTelegramBot = ConfigureTelegramBotUseCase(notificationSettingsStore, telegramBotApi),
                discoverTelegramChats = DiscoverTelegramChatsUseCase(notificationSettingsStore, telegramBotApi),
                selectTelegramChat = SelectTelegramChatUseCase(notificationSettingsStore, telegramBotApi),
                setTelegramNotificationsEnabled = SetTelegramNotificationsEnabledUseCase(notificationSettingsStore),
                clearTelegramBot = ClearTelegramBotUseCase(notificationSettingsStore),
                sendTelegramTest = SendTelegramTestUseCase(notificationSettingsStore, telegramBotApi),
                notifyClientUpdates = notifyClientUpdates,

                clientProgramCatalog = clientProgramCatalog,
                listClients = ListClientsUseCase(clientVersionRepository, clientProgramCatalog),
                previewClients = PreviewClientsUseCase(
                    clientVersionRepository,
                    clientProgramCatalog,
                    clientPreviewStore,
                    clientDownloadTracker,
                ),
                addClient = AddClientUseCase(
                    catalog = catalog,
                    versionRepository = clientVersionRepository,
                    programCatalog = clientProgramCatalog,
                    probeOne = probeClientProgram,
                    tokenProvider = githubTokenProvider,
                    backgroundScope = backgroundScope,
                    concurrency = backgroundConcurrency,
                ),
                probeClients = probeClients,
                syncClients = SyncClientsUseCase(
                    versionRepository = clientVersionRepository,
                    programCatalog = clientProgramCatalog,
                    downloadOne = downloadClientProgram,
                    tracker = clientDownloadTracker,
                    tokenProvider = githubTokenProvider,
                    backgroundScope = backgroundScope,
                    concurrency = backgroundConcurrency,
                ),
                deleteClient = DeleteClientUseCase(clientVersionRepository, Path.of(cfg.clientsDestDir)),
                resolveClientRelease = ResolveClientReleaseUseCase(
                    catalog = catalog,
                    clientReleaseResolvers = clientReleaseResolvers,
                    programs = clientProgramCatalog,
                    github = githubReleaseClient,
                ),
                githubTokenProvider = githubTokenProvider,
                clientsDestDir = Path.of(cfg.clientsDestDir),

                listServers = ListServersUseCase(serverRepository, serverMetricsRepository, nodeRepository),
                registerServer = RegisterServerUseCase(serverRepository, hostAgent, hostAgent),
                updateServer = UpdateServerUseCase(serverRepository, hostAgent, hostAgent),
                ingestServerMetrics = IngestServerMetricsUseCase(serverRepository, serverMetricsRepository),
                resolvePanelOrigin = ResolvePanelOriginUseCase(settingsStore, cfg.installOriginOverride),
                removeServer = removeServer,
                resumeServerRemovals = ResumeServerRemovalsUseCase(
                    servers = serverRepository,
                    finish = finishRemoveServer,
                    backgroundScope = backgroundScope,
                ),
                updateHostAgent = UpdateHostAgentUseCase(serverRepository, hostAgent),
                listNodes = ListNodesUseCase(nodeRepository, clientVersionRepository, networkFacts),
                getNode = GetNodeUseCase(nodeRepository, clientVersionRepository, networkFacts),
                addNode = AddNodeUseCase(
                    nodes = nodeRepository,
                    servers = serverRepository,
                    catalog = catalog,
                    clients = clientVersionRepository,
                    facts = networkFacts,
                ),
                removeNode = RemoveNodeUseCase(
                    nodes = nodeRepository,
                    servers = serverRepository,
                    resolveDestDir = { node -> resolveSnapshotDestDir(node) },
                    removeOnHost = HttpRemoveNodeOnHost(),
                ),
                getNodePorts = GetNodePortsUseCase(
                    nodes = nodeRepository,
                    servers = serverRepository,
                    catalog = clientProgramCatalog,
                ),
                checkHostPorts = CheckHostPortsUseCase(
                    servers = serverRepository,
                    catalog = clientProgramCatalog,
                    checkAgentPorts = hostAgent,
                ),
                getHostDisks = hostDisks,
                getHostSysctl = hostSysctl,
                getNodeDiskLayout = getNodeDiskLayout,
                saveNodeDiskLayout = SaveNodeDiskLayoutUseCase(nodeRepository),
                saveNodeInstallOptions = saveNodeInstallOptions,
                applyNodeClientConfig = applyNodeClientConfig,
                startNode = startNode,
                updateNodeClient = updateNodeClient,
                getNodeClientUpdate = getNodeClientUpdate,
                rollbackNodeClient = rollbackNodeClient,
                getNodeHeight = getNodeHeight,
                getNodeLogs = getNodeLogs,
                getNodeClientVersion = getNodeClientVersion,
                controlNodeProcess = controlNodeProcess,
                markNodeStarted = MarkNodeStartedUseCase(
                    serverRepository,
                    nodeRepository,
                    clientVersionRepository,
                    networkFacts,
                ),
                ingestNodeHeights = IngestNodeHeightsUseCase(
                    serverRepository,
                    nodeRepository,
                    clientVersionRepository,
                    networkFacts,
                    tipCache = tipCache,
                ),
                ingestClientUpdateProgress = IngestClientUpdateProgressUseCase(
                    servers = serverRepository,
                    nodes = nodeRepository,
                    store = clientUpdateProgress,
                ),
                clientUpdateProgress = clientUpdateProgress,
                updateNodeStatus = UpdateNodeStatusUseCase(nodeRepository),
                getNodeSnapshotPlan = GetNodeSnapshotPlanUseCase(
                    nodes = nodeRepository,
                    facts = networkFacts,
                    listSources = listSnapshotSources,
                    resolveDestDir = resolveSnapshotDestDir,
                ),
                probeNodeSnapshotSources = ProbeNodeSnapshotSourcesUseCase(
                    nodes = nodeRepository,
                    servers = serverRepository,
                    facts = networkFacts,
                    listSources = listSnapshotSources,
                    probeOnHost = snapshotHost,
                ),
                startNodeSnapshot = startNodeSnapshot,
                stopNodeSnapshot = StopNodeSnapshotUseCase(
                    nodes = nodeRepository,
                    servers = serverRepository,
                    stopOnHost = snapshotHost,
                ),
                getNodeSnapshotProgress = GetNodeSnapshotProgressUseCase(
                    nodes = nodeRepository,
                    servers = serverRepository,
                    pollHost = snapshotHost,
                ),
            )
        }
    }
}
