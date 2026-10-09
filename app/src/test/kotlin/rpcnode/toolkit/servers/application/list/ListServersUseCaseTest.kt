package rpcnode.toolkit.servers.application.list

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.servers.FakeServerMetricsRepository
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.domain.model.SERVER_REMOVE_STATUS_REMOVING
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerDisk
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.model.ServerMetrics
import rpcnode.toolkit.servers.domain.model.metricsStatus

class ListServersUseCaseTest
{
    private val server = Server(
        id = ServerId.parse("srv-1")!!,
        name = "box",
        agentUrl = "http://10.0.0.5:48990",
        createdAt = "t",
        updatedAt = "t",
    )

    @Test
    fun without_snapshot_status_is_unknown() = runTest {
        val listed = ListServersUseCase(
            FakeServerRepository(listOf(server)),
            FakeServerMetricsRepository(),
            FakeNodeRepository(),
        )()
        assertEquals(1, listed.size)
        assertEquals("unknown", listed[0].metricsStatus)
        assertEquals(true, listed[0].metricsStale)
        assertEquals(null, listed[0].metrics)
        assertEquals(true, listed[0].canDelete)
        assertEquals(0, listed[0].nodesCount)
    }

    @Test
    fun recent_snapshot_is_online_and_keeps_every_disk() = runTest {
        val now = java.time.Instant.now()
        val snap = ServerMetrics(
            serverId = server.id,
            cpuPct = 12.5,
            loadPct = 10.0,
            ncpu = 4,
            memPct = 40.0,
            memUsedMb = 8000.0,
            memTotalMb = 16000.0,
            diskUsedPct = 36.0,
            diskUsedGb = 900.0,
            diskTotalGb = 2500.0,
            load1 = 0.4,
            disks = listOf(
                ServerDisk("nvme0n1", "/", 100.0, 500.0, 80.0),
                ServerDisk("nvme1n1", "/data", 1500.0, 2000.0, 25.0),
            ),
            os = "linux",
            arch = "amd64",
            collectedAt = now.toString(),
            lastSeenAt = now.toString(),
        )
        val listed = ListServersUseCase(
            FakeServerRepository(listOf(server)),
            FakeServerMetricsRepository(listOf(snap)),
            FakeNodeRepository(),
        )()
        assertEquals("online", listed[0].metricsStatus)
        assertEquals(false, listed[0].metricsStale)
        assertEquals(2, listed[0].metrics!!.disks.size)
        assertEquals("nvme1n1", listed[0].metrics!!.disks[1].name)
    }

    @Test
    fun metrics_status_ages_to_stale_then_offline()
    {
        val now = java.time.Instant.parse("2026-08-31T12:00:00Z").toEpochMilli()
        val snap = { lastSeen: String ->
            ServerMetrics(
                serverId = server.id,
                cpuPct = 1.0,
                loadPct = 0.0,
                ncpu = 1,
                memPct = 1.0,
                memUsedMb = 1.0,
                memTotalMb = 2.0,
                diskUsedPct = 1.0,
                diskUsedGb = 1.0,
                diskTotalGb = 2.0,
                load1 = 0.0,
                disks = emptyList(),
                os = "",
                arch = "",
                collectedAt = lastSeen,
                lastSeenAt = lastSeen,
            )
        }
        assertEquals("stale", metricsStatus(snap("2026-08-31T11:55:00Z"), now))
        assertEquals("offline", metricsStatus(snap("2026-08-31T11:40:00Z"), now))
    }

    @Test
    fun soft_deleted_row_is_hidden() = runTest {
        val gone = server.copy(deletedAt = "2026-08-31T12:00:00Z")
        val listed = ListServersUseCase(
            FakeServerRepository(listOf(gone)),
            FakeServerMetricsRepository(),
            FakeNodeRepository(),
        )()
        assertEquals(0, listed.size)
    }

    @Test
    fun removing_server_shows_status_and_blocks_delete() = runTest {
        val removing = server.copy(removeStatus = SERVER_REMOVE_STATUS_REMOVING)
        val listed = ListServersUseCase(
            FakeServerRepository(listOf(removing)),
            FakeServerMetricsRepository(),
            FakeNodeRepository(),
        )()
        assertEquals(SERVER_REMOVE_STATUS_REMOVING, listed[0].metricsStatus)
        assertEquals(SERVER_REMOVE_STATUS_REMOVING, listed[0].removeStatus)
        assertEquals(false, listed[0].canDelete)
    }

    @Test
    fun nodes_on_the_host_block_delete() = runTest {
        val node = Node(
            id = NodeId.parse("n1")!!,
            serverId = server.id,
            name = "tron mainnet",
            network = NetworkId.TRON,
            env = EnvId.MAINNET,
            createdAt = "t",
            updatedAt = "t",
        )
        val listed = ListServersUseCase(
            FakeServerRepository(listOf(server)),
            FakeServerMetricsRepository(),
            FakeNodeRepository(listOf(node)),
        )()
        assertEquals(1, listed[0].nodesCount)
        assertEquals(false, listed[0].canDelete)
    }
}
