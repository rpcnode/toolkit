package rpcnode.toolkit.panel

import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Semaphore
import rpcnode.toolkit.catalog.application.LookupNetworkEnvUseCase
import rpcnode.toolkit.catalog.application.LookupNetworkUseCase
import rpcnode.toolkit.networks.application.connect.ListEthereumNodesUseCase
import rpcnode.toolkit.networks.application.connect.ListL1ParentChoicesUseCase
import rpcnode.toolkit.networks.application.tip.NetworkTipCache
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.networks.application.tip.NetworkTipProbeRegistry
import rpcnode.toolkit.nodes.application.height.GetNodeHeightUseCase
import rpcnode.toolkit.nodes.application.logs.FetchNodeLogsOnHost
import rpcnode.toolkit.nodes.application.logs.FetchNodeLogsResult
import rpcnode.toolkit.nodes.application.logs.GetNodeLogsUseCase
import rpcnode.toolkit.nodes.application.logs.NodeHostLogs
import rpcnode.toolkit.nodes.application.version.FetchNodeClientVersionOnHost
import rpcnode.toolkit.nodes.application.version.FetchNodeClientVersionResult
import rpcnode.toolkit.nodes.application.version.GetNodeClientVersionUseCase
import rpcnode.toolkit.nodes.application.version.NodeHostClientVersion
import rpcnode.toolkit.nodes.application.process.ControlNodeProcessOnHost
import rpcnode.toolkit.nodes.application.process.ControlNodeProcessUseCase
import rpcnode.toolkit.nodes.application.process.NodeProcessControlResult
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.bitcore.infrastructure.BitcoreChainWiring
import rpcnode.toolkit.chains.tron.infrastructure.start.TronNodeStart
import rpcnode.toolkit.chains.zcash.infrastructure.start.ZcashNodeStart
import rpcnode.toolkit.clients.FakeArtifactDownloader
import rpcnode.toolkit.clients.FakeClientProgramCatalog
import rpcnode.toolkit.clients.FakeClientVersionRepository
import rpcnode.toolkit.clients.FakeGitHubReleaseClient
import rpcnode.toolkit.clients.FakeGitHubTokenProvider
import rpcnode.toolkit.clients.application.ArtifactDownloader
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
import rpcnode.toolkit.clients.infrastructure.filesystem.FileClientManifestWriter
import rpcnode.toolkit.clients.infrastructure.filesystem.FileInstallPlanWriter
import rpcnode.toolkit.clients.infrastructure.tracking.InMemoryClientDownloadTracker
import rpcnode.toolkit.clients.infrastructure.tracking.InMemoryClientPreviewStore
import rpcnode.toolkit.networks.application.ClientFilesReadyChecker
import rpcnode.toolkit.networks.application.snapshot.CdnMirrorProbe
import rpcnode.toolkit.networks.application.snapshot.ListSnapshotSourcesUseCase
import rpcnode.toolkit.networks.application.snapshot.PreferCdnSnapshotUseCase
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.application.install.CheckNetworkInstallUseCase
import rpcnode.toolkit.networks.application.list.ListNetworksUseCase
import rpcnode.toolkit.networks.application.remove.RemoveNetworkUseCase
import rpcnode.toolkit.networks.application.setstatus.SetNetworkStatusUseCase
import rpcnode.toolkit.networks.application.snapshot.ResolveSnapshotUseCase
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.networks.infrastructure.filesystem.DiskClientFilesReadyChecker
import rpcnode.toolkit.networks.infrastructure.persistence.SqliteNetworkRepository
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.application.add.AddNodeUseCase
import rpcnode.toolkit.nodes.application.disks.GetNodeDiskLayoutUseCase
import rpcnode.toolkit.nodes.application.disks.GetHostDisksUseCase
import rpcnode.toolkit.nodes.application.disks.HostDiskReader
import rpcnode.toolkit.nodes.application.disks.SaveNodeDiskLayoutUseCase
import rpcnode.toolkit.nodes.application.sysctl.GetHostSysctlUseCase
import rpcnode.toolkit.nodes.application.sysctl.HostSysctlReader
import rpcnode.toolkit.nodes.application.get.GetNodeUseCase
import rpcnode.toolkit.nodes.application.list.ListNodesUseCase
import rpcnode.toolkit.nodes.application.options.SaveNodeInstallOptionsUseCase
import rpcnode.toolkit.nodes.application.config.ApplyNodeClientConfigUseCase
import rpcnode.toolkit.nodes.application.config.ClientSyncOnHostResult
import rpcnode.toolkit.nodes.application.config.SyncClientOnHost
import rpcnode.toolkit.nodes.application.ingest.IngestNodeHeightsUseCase
import rpcnode.toolkit.nodes.application.ingest.MarkNodeStartedUseCase
import rpcnode.toolkit.nodes.application.start.StartNodeOnHost
import rpcnode.toolkit.nodes.application.start.StartNodeOnHostResult
import rpcnode.toolkit.nodes.application.start.StartNodeUseCase
import rpcnode.toolkit.nodes.application.update.ClientRollbackOnHostResult
import rpcnode.toolkit.nodes.application.update.ClientUpdateInfo
import rpcnode.toolkit.nodes.application.update.ClientUpdateOnHostCommand
import rpcnode.toolkit.nodes.application.update.ClientUpdateOnHostResult
import rpcnode.toolkit.nodes.application.update.ClientUpdateProgressStore
import rpcnode.toolkit.nodes.application.update.ClientUpdateStatusOnHostResult
import rpcnode.toolkit.nodes.application.update.GetNodeClientUpdateUseCase
import rpcnode.toolkit.nodes.application.update.IngestClientUpdateProgressUseCase
import rpcnode.toolkit.nodes.application.update.RollbackNodeClientUseCase
import rpcnode.toolkit.nodes.application.update.UpdateClientOnHost
import rpcnode.toolkit.nodes.application.update.UpdateNodeClientUseCase
import rpcnode.toolkit.nodes.application.ports.CheckHostPortsUseCase
import rpcnode.toolkit.nodes.application.ports.GetNodePortsUseCase
import rpcnode.toolkit.nodes.application.remove.RemoveNodeOnHost
import rpcnode.toolkit.nodes.application.remove.RemoveNodeOnHostResult
import rpcnode.toolkit.nodes.application.remove.RemoveNodeUseCase
import rpcnode.toolkit.nodes.application.snapshot.GetNodeSnapshotPlanUseCase
import rpcnode.toolkit.nodes.application.snapshot.ResolveSnapshotDestDirUseCase
import rpcnode.toolkit.nodes.application.snapshot.GetNodeSnapshotProgressUseCase
import rpcnode.toolkit.nodes.application.snapshot.ProbeNodeSnapshotSourcesUseCase
import rpcnode.toolkit.nodes.application.snapshot.ProbeSnapshotOnHost
import rpcnode.toolkit.nodes.application.snapshot.PollSnapshotOnHost
import rpcnode.toolkit.nodes.application.snapshot.SnapshotHostProgress
import rpcnode.toolkit.nodes.application.snapshot.SnapshotHostSpeedResult
import rpcnode.toolkit.nodes.application.snapshot.SnapshotHostSpeedSample
import rpcnode.toolkit.nodes.application.snapshot.StartNodeSnapshotUseCase
import rpcnode.toolkit.nodes.application.snapshot.StartSnapshotOnHost
import rpcnode.toolkit.nodes.application.snapshot.StopNodeSnapshotUseCase
import rpcnode.toolkit.nodes.application.snapshot.StopSnapshotOnHost
import rpcnode.toolkit.nodes.application.status.UpdateNodeStatusUseCase
import rpcnode.toolkit.networks.infrastructure.catalog.YamlSnapshotMirrorCatalog
import rpcnode.toolkit.chains.tron.infrastructure.http.TronSnapshotResolver
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.auth.application.login.LoginUseCase
import rpcnode.toolkit.auth.application.logout.LogoutUseCase
import rpcnode.toolkit.auth.application.status.GetAuthStatusUseCase
import rpcnode.toolkit.auth.infrastructure.persistence.HtpasswdCredentialStore
import rpcnode.toolkit.auth.infrastructure.persistence.MemorySessionStore
import rpcnode.toolkit.servers.FakeServerMetricsRepository
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.application.ingest.IngestServerMetricsUseCase
import rpcnode.toolkit.servers.application.list.ListServersUseCase
import rpcnode.toolkit.servers.application.origin.ResolvePanelOriginUseCase
import rpcnode.toolkit.servers.application.probe.CheckAgentPorts
import rpcnode.toolkit.servers.application.probe.EnrollHostAgent
import rpcnode.toolkit.servers.application.probe.EnrollHostAgentResult
import rpcnode.toolkit.servers.application.probe.HostAgentClient
import rpcnode.toolkit.servers.application.probe.UnenrollHostAgent
import rpcnode.toolkit.servers.application.probe.UpdateHostAgent
import rpcnode.toolkit.servers.application.register.RegisterServerUseCase
import rpcnode.toolkit.servers.application.remove.FinishRemoveServerUseCase
import rpcnode.toolkit.servers.application.remove.RemoveServerUseCase
import rpcnode.toolkit.servers.application.remove.ResumeServerRemovalsUseCase
import rpcnode.toolkit.servers.application.update.UpdateHostAgentUseCase
import rpcnode.toolkit.servers.application.update.UpdateServerUseCase
import rpcnode.toolkit.servers.domain.repository.ServerMetricsRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository
import rpcnode.toolkit.settings.application.get.GetSettingsUseCase
import rpcnode.toolkit.settings.application.get.InstallStampReader
import rpcnode.toolkit.settings.application.get.UrlProbe
import rpcnode.toolkit.settings.application.save.GitHubTokenCheck
import rpcnode.toolkit.settings.application.save.GitHubTokenChecker
import rpcnode.toolkit.settings.application.save.SaveSettingsUseCase
import rpcnode.toolkit.settings.infrastructure.persistence.AesGcmSecretBox
import rpcnode.toolkit.settings.infrastructure.persistence.DiskInstallFiles
import rpcnode.toolkit.settings.infrastructure.persistence.SqliteSettingsStore
import rpcnode.toolkit.notifications.application.ClearTelegramBotUseCase
import rpcnode.toolkit.notifications.application.ConfigureTelegramBotUseCase
import rpcnode.toolkit.notifications.application.DiscoverTelegramChatsUseCase
import rpcnode.toolkit.notifications.application.GetTelegramNotificationSettingsUseCase
import rpcnode.toolkit.notifications.application.NotifyClientUpdatesUseCase
import rpcnode.toolkit.notifications.application.SelectTelegramChatUseCase
import rpcnode.toolkit.notifications.application.SendTelegramTestUseCase
import rpcnode.toolkit.notifications.application.SetTelegramNotificationsEnabledUseCase
import rpcnode.toolkit.notifications.application.TelegramBotApi
import rpcnode.toolkit.notifications.application.TelegramBotApiResult
import rpcnode.toolkit.notifications.domain.model.TelegramBot
import rpcnode.toolkit.notifications.domain.model.TelegramBotToken
import rpcnode.toolkit.notifications.domain.model.TelegramChat
import rpcnode.toolkit.notifications.domain.model.TelegramChatMemberStatus
import rpcnode.toolkit.notifications.infrastructure.persistence.SqliteNotificationSettingsStore
import rpcnode.toolkit.setup.application.create.CreateAdminUseCase
import rpcnode.toolkit.setup.application.status.GetSetupStatusUseCase
import rpcnode.toolkit.shared.infrastructure.persistence.ToolkitDatabase
import rpcnode.toolkit.wiring.Toolkit

internal fun testToolkit(
    githubChecker: GitHubTokenChecker = GitHubTokenChecker { GitHubTokenCheck.Ok },
    urlProbe: UrlProbe = UrlProbe { false },
    clientFilesReady: ClientFilesReadyChecker? = null,
    snapshotResolvers: Map<NetworkId, SnapshotResolver> = mapOf(
        NetworkId.TRON to TronSnapshotResolver(mirrors = YamlSnapshotMirrorCatalog()),
    ),
    cdnMirrorProbe: CdnMirrorProbe = object : CdnMirrorProbe
    {
        override suspend fun versionText(url: String): String? = null
        override suspend fun archivePresent(url: String): Boolean = false
    },
    clientReleaseResolvers: Map<NetworkId, ClientReleaseResolver> = emptyMap(),
    clientVersionRepository: ClientVersionRepository = FakeClientVersionRepository(),
    clientProgramCatalog: ClientProgramCatalog = FakeClientProgramCatalog(),
    githubReleaseClient: GitHubReleaseClient = FakeGitHubReleaseClient(),
    artifactDownloader: ArtifactDownloader = FakeArtifactDownloader(),
    githubTokenProvider: GitHubTokenProvider? = null,
    serverRepository: ServerRepository = FakeServerRepository(),
    serverMetricsRepository: ServerMetricsRepository = FakeServerMetricsRepository(),
    enrollHostAgent: EnrollHostAgent = EnrollHostAgent { _, _, _, _ -> EnrollHostAgentResult.Ok },
    hostAgentClient: HostAgentClient = HostAgentClient { _, _ -> null },
    unenrollHostAgent: UnenrollHostAgent = UnenrollHostAgent { _, _ -> true },
    updateHostAgent: UpdateHostAgent = UpdateHostAgent { _, _, _ -> null },
    checkAgentPorts: CheckAgentPorts = CheckAgentPorts { _, _, _ -> null },
    hostDiskReader: HostDiskReader = HostDiskReader { _, _ -> null },
    hostSysctlReader: HostSysctlReader = HostSysctlReader { _, _ -> null },
    nodeRepository: NodeRepository = FakeNodeRepository(),
    telegramBotApi: TelegramBotApi = testTelegramBotApi,
): Toolkit
{
    val dir = Files.createTempDirectory("panel-auth")
    val mapping = YamlNetworkFactsRepository()
    val catalog = mapping
    val db = ToolkitDatabase(dir.resolve("toolkit.db"))

    // Auth
    val credentials = HtpasswdCredentialStore(dir.resolve("panel.htpasswd"), bcryptRounds = 4)
    val sessions = MemorySessionStore()

    // Settings
    val settingsStore = SqliteSettingsStore(
        db = db,
        githubTokenFile = dir.resolve("github-token"),
        secrets = AesGcmSecretBox(dir.resolve("panel.notify.key")),
    )
    val getSettings = GetSettingsUseCase(
        store = settingsStore,
        probe = urlProbe,
        installFiles = DiskInstallFiles(dir.resolve("install")),
        envOrigin = null,
        envSnapshotCdnOrigin = null,
        panelVersion = "test",
        installStamp = InstallStampReader { null },
    )
    val notificationSettingsStore = SqliteNotificationSettingsStore(
        db = db,
        secrets = AesGcmSecretBox(dir.resolve("panel.notify.key")),
    )
    // Networks
    val networkRepository = SqliteNetworkRepository(db)
    val filesReady = clientFilesReady ?: DiskClientFilesReadyChecker(dir.resolve("clients"))
    val networkFacts = mapping
    val resolveSnapshot = ResolveSnapshotUseCase(catalog, snapshotResolvers)
    val listSnapshotSources = ListSnapshotSourcesUseCase(
        resolve = resolveSnapshot,
        store = settingsStore,
        probe = cdnMirrorProbe,
        envSnapshotCdnOrigin = null,
    )
    val preferCdnSnapshot = PreferCdnSnapshotUseCase(listSnapshotSources)

    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val backgroundConcurrency = Semaphore(4)

    // Clients
    val tokenProvider = githubTokenProvider ?: FakeGitHubTokenProvider(null)
    val clientDownloadTracker = InMemoryClientDownloadTracker()
    val clientPreviewStore = InMemoryClientPreviewStore()
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
        tokenProvider = tokenProvider,
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
        destDir = dir.resolve("clients"),
        clientReleaseResolvers = clientReleaseResolvers,
    )

    val finishRemoveServer = FinishRemoveServerUseCase(serverRepository, unenrollHostAgent)

    val hostDisksUseCase = GetHostDisksUseCase(
        servers = serverRepository,
        reader = hostDiskReader,
    )
    val hostSysctlUseCase = GetHostSysctlUseCase(
        servers = serverRepository,
        reader = hostSysctlReader,
    )
    val getNodeDiskLayoutUseCase = GetNodeDiskLayoutUseCase(
        nodes = nodeRepository,
        facts = networkFacts,
        hostDisks = hostDisksUseCase,
    )
    val resolveSnapshotDestDir = ResolveSnapshotDestDirUseCase(getNodeDiskLayoutUseCase, networkFacts)
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
        syncOnHost = SyncClientOnHost { _, _, command ->
            ClientSyncOnHostResult.Ok(nodeDir = command.nodeDir, files = emptyList(), configPath = null)
        },
        installPlanWriter = FileInstallPlanWriter(),
        clientsDestDir = dir.resolve("clients"),
        downloadClient = downloadClientProgram,
    )
    val startNode = StartNodeUseCase(
        saveInstallOptions = saveNodeInstallOptions,
        nodes = nodeRepository,
        servers = serverRepository,
        facts = networkFacts,
        catalog = clientProgramCatalog,
        clients = clientVersionRepository,
        resolveDestDir = resolveSnapshotDestDir,
        startOnHost = StartNodeOnHost { _, _, _ ->
            StartNodeOnHostResult.Ok(pid = 4242L)
        },
        chainStarts = BitcoreChainWiring.chainStarts() +
            mapOf(
                NetworkId.TRON to TronNodeStart(),
                NetworkId.ZCASH to ZcashNodeStart(),
            ),
    )
    val updateClientOnHost = object : UpdateClientOnHost
    {
        override suspend fun update(agentUrl: String, token: String, command: ClientUpdateOnHostCommand) =
            ClientUpdateOnHostResult.Accepted(ClientUpdateInfo(phase = "updating", step = "check", pct = 5))

        override suspend fun status(agentUrl: String, token: String, nodeId: String, network: String, env: String) =
            ClientUpdateStatusOnHostResult.Ok(ClientUpdateInfo())

        override suspend fun rollback(agentUrl: String, token: String, nodeId: String, network: String, env: String) =
            ClientRollbackOnHostResult.Ok(ClientUpdateInfo(phase = "idle", step = "done", local = "1.0"))
    }
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
        clientsDestDir = dir.resolve("clients"),
        downloadClient = downloadClientProgram,
        chainStarts = BitcoreChainWiring.chainStarts() +
            mapOf(
                NetworkId.TRON to TronNodeStart(),
                NetworkId.ZCASH to ZcashNodeStart(),
            ),
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
    val startNodeSnapshot = StartNodeSnapshotUseCase(
        nodes = nodeRepository,
        servers = serverRepository,
        facts = networkFacts,
        saveInstallOptions = saveNodeInstallOptions,
        preferSnapshot = preferCdnSnapshot,
        resolveDestDir = resolveSnapshotDestDir,
        startOnHost = testSnapshotHost,
    )
    return Toolkit(
        catalog = catalog,
        networkFacts = networkFacts,
        lookupNetwork = LookupNetworkUseCase(catalog),
        lookupNetworkEnv = LookupNetworkEnvUseCase(catalog),
        listNetworks = ListNetworksUseCase(catalog, networkRepository, filesReady, networkFacts),
        setNetworkStatus = SetNetworkStatusUseCase(catalog, networkRepository),
        removeNetwork = RemoveNetworkUseCase(networkRepository),
        checkNetworkInstall = CheckNetworkInstallUseCase(catalog, filesReady),
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
        previewClients = PreviewClientsUseCase(clientVersionRepository, clientProgramCatalog, clientPreviewStore, clientDownloadTracker),
        addClient = AddClientUseCase(
            catalog = catalog,
            versionRepository = clientVersionRepository,
            programCatalog = clientProgramCatalog,
            probeOne = probeClientProgram,
            tokenProvider = tokenProvider,
            backgroundScope = backgroundScope,
            concurrency = backgroundConcurrency,
        ),
        probeClients = probeClients,
        syncClients = SyncClientsUseCase(
            versionRepository = clientVersionRepository,
            programCatalog = clientProgramCatalog,
            downloadOne = downloadClientProgram,
            tracker = clientDownloadTracker,
            tokenProvider = tokenProvider,
            backgroundScope = backgroundScope,
            concurrency = backgroundConcurrency,
        ),
        deleteClient = DeleteClientUseCase(clientVersionRepository, dir.resolve("clients")),
        resolveClientRelease = ResolveClientReleaseUseCase(
            catalog = catalog,
            clientReleaseResolvers = clientReleaseResolvers,
            programs = clientProgramCatalog,
            github = githubReleaseClient,
        ),
        githubTokenProvider = tokenProvider,
        clientsDestDir = dir.resolve("clients"),

        listServers = ListServersUseCase(serverRepository, serverMetricsRepository, nodeRepository),
        registerServer = RegisterServerUseCase(serverRepository, enrollHostAgent, hostAgentClient),
        updateServer = UpdateServerUseCase(serverRepository, enrollHostAgent, hostAgentClient),
        ingestServerMetrics = IngestServerMetricsUseCase(serverRepository, serverMetricsRepository),
        resolvePanelOrigin = ResolvePanelOriginUseCase(settingsStore, null),
        removeServer = RemoveServerUseCase(
            servers = serverRepository,
            nodes = nodeRepository,
            finish = finishRemoveServer,
            backgroundScope = backgroundScope,
        ),
        resumeServerRemovals = ResumeServerRemovalsUseCase(
            servers = serverRepository,
            finish = finishRemoveServer,
            backgroundScope = backgroundScope,
        ),
        updateHostAgent = UpdateHostAgentUseCase(serverRepository, updateHostAgent),
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
            removeOnHost = RemoveNodeOnHost { _, _, _ -> RemoveNodeOnHostResult.Ok },
        ),
        getNodePorts = GetNodePortsUseCase(
            nodes = nodeRepository,
            servers = serverRepository,
            catalog = clientProgramCatalog,
        ),
        checkHostPorts = CheckHostPortsUseCase(
            servers = serverRepository,
            catalog = clientProgramCatalog,
            checkAgentPorts = checkAgentPorts,
        ),
        getHostDisks = hostDisksUseCase,
        getHostSysctl = hostSysctlUseCase,
        getNodeDiskLayout = getNodeDiskLayoutUseCase,
        saveNodeDiskLayout = SaveNodeDiskLayoutUseCase(nodeRepository),
        saveNodeInstallOptions = saveNodeInstallOptions,
        applyNodeClientConfig = applyNodeClientConfig,
        startNode = startNode,
        updateNodeClient = updateNodeClient,
        getNodeClientUpdate = getNodeClientUpdate,
        rollbackNodeClient = rollbackNodeClient,
        getNodeHeight = GetNodeHeightUseCase(
            nodes = nodeRepository,
            tipCache = NetworkTipCache(
                facts = networkFacts,
                tipProbes = NetworkTipProbeRegistry(
                    mapOf(
                        NetworkId.TRON to NetworkTipProbe { null },
                        NetworkId.BITCOIN to NetworkTipProbe { null },
                    ),
                ),
            ),
        ),
        getNodeLogs = GetNodeLogsUseCase(
            nodes = nodeRepository,
            servers = serverRepository,
            facts = networkFacts,
            catalog = clientProgramCatalog,
            resolveDestDir = resolveSnapshotDestDir,
            fetchOnHost = FetchNodeLogsOnHost { _, _, _, _, _, _ ->
                FetchNodeLogsResult.Ok(NodeHostLogs())
            },
        ),
        getNodeClientVersion = GetNodeClientVersionUseCase(
            nodes = nodeRepository,
            servers = serverRepository,
            facts = networkFacts,
            clients = clientVersionRepository,
            resolveDestDir = resolveSnapshotDestDir,
            fetchOnHost = FetchNodeClientVersionOnHost { _, _, _, _, _ ->
                FetchNodeClientVersionResult.Ok(NodeHostClientVersion())
            },
        ),
        controlNodeProcess = ControlNodeProcessUseCase(
            nodes = nodeRepository,
            servers = serverRepository,
            controlOnHost = ControlNodeProcessOnHost { _, _, _, _, _, _ ->
                NodeProcessControlResult(ok = true, pid = 1, action = "start")
            },
            startNode = startNode,
        ),
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
            tipCache = NetworkTipCache(
                facts = networkFacts,
                tipProbes = NetworkTipProbeRegistry(
                    mapOf(
                        NetworkId.TRON to NetworkTipProbe { null },
                        NetworkId.BITCOIN to NetworkTipProbe { null },
                    ),
                ),
            ),
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
            probeOnHost = testSnapshotHost,
        ),
        startNodeSnapshot = startNodeSnapshot,
        stopNodeSnapshot = StopNodeSnapshotUseCase(
            nodes = nodeRepository,
            servers = serverRepository,
            stopOnHost = testSnapshotHost,
        ),
        getNodeSnapshotProgress = GetNodeSnapshotProgressUseCase(
            nodes = nodeRepository,
            servers = serverRepository,
            pollHost = testSnapshotHost,
        ),
    )
}

private val testTelegramBotApi = object : TelegramBotApi
{
    override suspend fun getMe(token: TelegramBotToken): TelegramBotApiResult<TelegramBot> =
        TelegramBotApiResult.Ok(TelegramBot(id = 123L, username = "toolkit_test_bot"))

    override suspend fun getUpdates(token: TelegramBotToken): TelegramBotApiResult<List<TelegramChat>> =
        TelegramBotApiResult.Ok(emptyList())

    override suspend fun getChatMember(
        token: TelegramBotToken,
        chatId: Long,
        userId: Long,
    ): TelegramBotApiResult<TelegramChatMemberStatus> =
        TelegramBotApiResult.Ok(TelegramChatMemberStatus.ADMINISTRATOR)

    override suspend fun sendMessage(
        token: TelegramBotToken,
        chatId: Long,
        text: String,
    ): TelegramBotApiResult<Unit> = TelegramBotApiResult.Ok(Unit)
}

private val testSnapshotHost = object : StartSnapshotOnHost, PollSnapshotOnHost, StopSnapshotOnHost, ProbeSnapshotOnHost
{
    override suspend fun start(agentUrl: String, token: String, command: rpcnode.toolkit.nodes.application.snapshot.SnapshotHostStartCommand): Boolean? = true

    override suspend fun progress(agentUrl: String, token: String, jobId: String): SnapshotHostProgress? =
        SnapshotHostProgress(pct = 100.0, phase = "complete", detail = "ready", ready = true)

    override suspend fun stop(agentUrl: String, token: String, jobId: String, wipeDest: Boolean): Boolean? = true

    override suspend fun probe(
        agentUrl: String,
        token: String,
        samples: List<SnapshotHostSpeedSample>,
    ): List<SnapshotHostSpeedResult>? =
        samples.map {
            SnapshotHostSpeedResult(
                id = it.id,
                available = true,
                bytesPerSec = 10_000_000L,
                sampleBytes = 1L shl 20,
                latencyMs = 50L,
                detail = "test probe",
            )
        }
}
