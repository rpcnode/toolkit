package rpcnode.toolkit.nodes.application.height

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.application.tip.NetworkTipCache
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.networks.application.tip.NetworkTipProbeRegistry
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.servers.domain.model.ServerId

class GetNodeHeightUseCaseTest
{
    private val nodeId = NodeId.parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")!!
    private val serverId = ServerId.parse("srv-1")!!

    private fun node(status: NodeStatus, height: Long = 100, networkHeight: Long = 0) = Node(
        id = nodeId,
        serverId = serverId,
        name = "TRON nile",
        network = NetworkId.TRON,
        env = EnvId.NILE,
        status = status,
        height = height,
        heightAt = "t",
        networkHeight = networkHeight,
        createdAt = "t",
        updatedAt = "t",
    )

    private fun useCase(
        nodes: FakeNodeRepository,
        tip: Long? = 200,
    ): GetNodeHeightUseCase
    {
        val facts = YamlNetworkFactsRepository()
        return GetNodeHeightUseCase(
            nodes = nodes,
            tipCache = NetworkTipCache(
                facts = facts,
                tipProbes = NetworkTipProbeRegistry(
                    mapOf(NetworkId.TRON to NetworkTipProbe { tip }),
                ),
            ),
            tipLagActive = 3,
            clock = { "now" },
        )
    }

    @Test
    fun always_returns_height_for_any_status() = runTest {
        val nodes = FakeNodeRepository(listOf(node(NodeStatus.parse("starting"), height = 12)))
        val result = useCase(nodes)(nodeId.value)
        val ok = assertIs<GetNodeHeightResult.Ok>(result)
        assertEquals(12, ok.view.height)
        assertEquals("starting", ok.view.status)
    }

    @Test
    fun returns_height_and_behind_while_sync() = runTest {
        val nodes = FakeNodeRepository(listOf(node(NodeStatus.SYNC, height = 90)))
        val result = useCase(nodes, tip = 100)(nodeId.value)
        val ok = assertIs<GetNodeHeightResult.Ok>(result)
        assertEquals(90, ok.view.height)
        assertEquals(100, ok.view.networkHeight)
        assertEquals(10, ok.view.behind)
        assertEquals(NodeStatus.SYNC.value, ok.view.status)
    }

    @Test
    fun promotes_to_active_when_within_tip_lag() = runTest {
        val nodes = FakeNodeRepository(listOf(node(NodeStatus.SYNC, height = 98)))
        val result = useCase(nodes, tip = 100)(nodeId.value)
        val ok = assertIs<GetNodeHeightResult.Ok>(result)
        assertEquals(NodeStatus.ACTIVE.value, ok.view.status)
        assertEquals(2, ok.view.behind)
        assertEquals(NodeStatus.ACTIVE.value, nodes.findById(nodeId)!!.status.value)
    }

    @Test
    fun does_not_promote_when_host_sync_pct_incomplete() = runTest {
        val nodes = FakeNodeRepository(
            listOf(node(NodeStatus.SYNC, height = 98).copy(syncPct = 18.5)),
        )
        val result = useCase(nodes, tip = 100)(nodeId.value)
        val ok = assertIs<GetNodeHeightResult.Ok>(result)
        assertEquals(NodeStatus.SYNC.value, ok.view.status)
        assertEquals(18.5, ok.view.syncPct)
        assertEquals(NodeStatus.SYNC.value, nodes.findById(nodeId)!!.status.value)
    }

    @Test
    fun tip_falls_back_to_stored_network_height() = runTest {
        val nodes = FakeNodeRepository(
            listOf(node(NodeStatus.SYNC, height = 50, networkHeight = 80)),
        )
        val result = useCase(nodes, tip = null)(nodeId.value)
        val ok = assertIs<GetNodeHeightResult.Ok>(result)
        assertEquals(80, ok.view.networkHeight)
        assertEquals(30, ok.view.behind)
    }

    @Test
    fun tip_null_when_probe_fails_and_no_stored() = runTest {
        val nodes = FakeNodeRepository(listOf(node(NodeStatus.SYNC, height = 50)))
        val result = useCase(nodes, tip = null)(nodeId.value)
        val ok = assertIs<GetNodeHeightResult.Ok>(result)
        assertNull(ok.view.networkHeight)
        assertNull(ok.view.behind)
        assertEquals(NodeStatus.SYNC.value, ok.view.status)
    }
}
