package rpcnode.toolkit.nodes.application.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.FakeClientVersionRepository
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.networks.application.tip.NetworkTipCache
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.networks.application.tip.NetworkTipProbeRegistry
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

class MarkNodeStartedUseCaseTest
{
    private val serverId = ServerId.parse("srv-1")!!
    private val nodeId = NodeId.parse("11111111-1111-4111-8111-111111111111")!!
    private val server = Server(
        id = serverId,
        name = "Local",
        agentUrl = "http://127.0.0.1:9",
        agentKey = "tok",
        createdAt = "t",
        updatedAt = "t",
    )
    private val node = Node(
        id = nodeId,
        serverId = serverId,
        name = "TRON nile",
        network = NetworkId.TRON,
        env = EnvId.NILE,
        createdAt = "t",
        updatedAt = "t",
    )

    @Test
    fun started_hook_records_client_version() = runTest {
        val nodes = FakeNodeRepository(listOf(node))
        val clients = FakeClientVersionRepository(
            listOf(
                ClientVersionPin(
                    network = NetworkId.TRON,
                    env = EnvId.NILE,
                    program = "FullNode.jar",
                    currentVersion = "GreatVoyage-Nile-v4.8.2.1",
                    latestVersion = "GreatVoyage-Nile-v4.8.3",
                ),
            ),
        )
        val useCase = MarkNodeStartedUseCase(
            servers = FakeServerRepository(listOf(server)),
            nodes = nodes,
            clients = clients,
            facts = YamlNetworkFactsRepository(),
        )

        val result = useCase("tok", serverId.value, nodeId.value, "GreatVoyage-Nile-v4.8.2.1")

        assertIs<MarkNodeStartedResult.Ok>(result)
        val updated = nodes.findById(nodeId)!!
        assertEquals(NodeStatus.SYNC, updated.status)
        assertEquals("GreatVoyage-Nile-v4.8.2.1", updated.clientVersion)
        assertEquals("GreatVoyage-Nile-v4.8.3", updated.clientLatest)
        assertTrue(updated.clientUpdateAvailable)
    }

    @Test
    fun height_hook_updates_client_version_when_changed() = runTest {
        val nodes = FakeNodeRepository(listOf(node.copy(clientVersion = "old")))
        val facts = YamlNetworkFactsRepository()
        val useCase = IngestNodeHeightsUseCase(
            servers = FakeServerRepository(listOf(server)),
            nodes = nodes,
            clients = FakeClientVersionRepository(
                listOf(
                    ClientVersionPin(
                        network = NetworkId.TRON,
                        env = EnvId.NILE,
                        program = "FullNode.jar",
                        currentVersion = "v2",
                        latestVersion = "v2",
                    ),
                ),
            ),
            facts = facts,
            tipCache = NetworkTipCache(
                facts = facts,
                tipProbes = NetworkTipProbeRegistry(
                    mapOf(NetworkId.TRON to NetworkTipProbe { 999L }),
                ),
            ),
        )

        val result = useCase(
            "tok",
            serverId.value,
            listOf(
                NodeHeightSample(
                    nodeId = nodeId.value,
                    height = 42,
                    clientVersion = "v2",
                    sizeOnDisk = 5_368_709_120L,
                ),
            ),
        )

        assertIs<IngestNodeHeightsResult.Ok>(result)
        assertEquals(1, result.updated)
        val updated = nodes.findById(nodeId)!!
        assertEquals(42, updated.height)
        assertEquals(999L, updated.networkHeight)
        assertEquals(5_368_709_120L, updated.sizeOnDisk)
        assertEquals("v2", updated.clientVersion)
        assertEquals("v2", updated.clientLatest)
        assertFalse(updated.clientUpdateAvailable)
    }

    @Test
    fun height_push_persists_sync_pct_and_stays_sync_while_host_syncing() = runTest {
        val nodes = FakeNodeRepository(listOf(node.copy(status = NodeStatus.SYNC, height = 0)))
        val clients = FakeClientVersionRepository()
        val useCase = IngestNodeHeightsUseCase(
            servers = FakeServerRepository(listOf(server)),
            nodes = nodes,
            clients = clients,
            facts = YamlNetworkFactsRepository(),
            tipCache = NetworkTipCache(
                facts = YamlNetworkFactsRepository(),
                tipProbes = NetworkTipProbeRegistry(
                    mapOf(NetworkId.TRON to NetworkTipProbe { 100L }),
                ),
            ),
            tipLagActive = 3,
            clock = { "now" },
        )

        val result = useCase(
            "tok",
            serverId.value,
            listOf(
                NodeHeightSample(
                    nodeId = nodeId.value,
                    height = 99,
                    syncPct = 18.5,
                    syncing = true,
                ),
            ),
        )

        assertIs<IngestNodeHeightsResult.Ok>(result)
        val updated = nodes.findById(nodeId)!!
        assertEquals(18.5, updated.syncPct)
        assertEquals(99, updated.height)
        assertEquals(NodeStatus.SYNC.value, updated.status.value)
    }

    @Test
    fun height_push_persists_sync_pct_when_height_unknown() = runTest {
        val nodes = FakeNodeRepository(listOf(node.copy(status = NodeStatus.SYNC, height = 0)))
        val clients = FakeClientVersionRepository()
        val useCase = IngestNodeHeightsUseCase(
            servers = FakeServerRepository(listOf(server)),
            nodes = nodes,
            clients = clients,
            facts = YamlNetworkFactsRepository(),
            tipCache = NetworkTipCache(
                facts = YamlNetworkFactsRepository(),
                tipProbes = NetworkTipProbeRegistry(
                    mapOf(NetworkId.TRON to NetworkTipProbe { 100L }),
                ),
            ),
            tipLagActive = 3,
            clock = { "now" },
        )

        val result = useCase(
            "tok",
            serverId.value,
            listOf(
                NodeHeightSample(
                    nodeId = nodeId.value,
                    height = -1,
                    syncPct = 12.5,
                    syncing = true,
                ),
            ),
        )

        assertIs<IngestNodeHeightsResult.Ok>(result)
        val updated = nodes.findById(nodeId)!!
        assertEquals(12.5, updated.syncPct)
        assertEquals(0, updated.height)
        assertEquals(NodeStatus.SYNC.value, updated.status.value)
    }
}
