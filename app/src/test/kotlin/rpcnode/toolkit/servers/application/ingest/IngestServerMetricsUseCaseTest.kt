package rpcnode.toolkit.servers.application.ingest

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.servers.FakeServerMetricsRepository
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

class IngestServerMetricsUseCaseTest
{
    private val clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneOffset.UTC)
    private val server = Server(
        id = ServerId.parse("srv-1")!!,
        name = "box",
        agentUrl = "http://10.0.0.5:48990",
        agentKey = "tok",
        createdAt = "t",
        updatedAt = "t",
    )

    private fun incoming() = IncomingHostMetrics(
        serverId = "srv-1",
        cpuPct = 12.5,
        load1 = 0.4,
        loadPct = 10.0,
        ncpu = 4,
        memPct = 40.0,
        memUsedMb = 8000.0,
        memTotalMb = 16000.0,
        diskUsedPct = 0.0,
        diskUsedGb = 0.0,
        diskTotalGb = 0.0,
        disks = listOf(
            IncomingHostDisk("nvme0n1", "/", 100.0, 500.0, 80.0, readIops = 12.0, utilPct = 33.0),
            IncomingHostDisk("nvme1n1", "/data", 1500.0, 2000.0, 25.0),
        ),
        os = "linux",
        arch = "amd64",
        netRxMbps = 1.5,
        netTxMbps = 0.25,
        diskReadIops = 12.0,
        diskWriteIops = 4.0,
        diskReadMbS = 0.8,
        diskWriteMbS = 0.2,
        diskUtilPct = 33.0,
        diskBusy = "nvme0n1",
    )

    @Test
    fun matching_token_writes_every_disk() = runTest {
        val metrics = FakeServerMetricsRepository()
        val result = IngestServerMetricsUseCase(FakeServerRepository(listOf(server)), metrics, clock)("tok", incoming())
        val ok = assertIs<IngestServerMetricsResult.Ok>(result)
        assertEquals("srv-1", ok.serverId.value)
        assertEquals("online", ok.status)
        val snap = metrics.find(server.id)!!
        assertEquals(12.5, snap.cpuPct)
        assertEquals(2500.0, snap.diskTotalGb)
        assertEquals(2, snap.disks.size)
        assertEquals("/data", snap.disks[1].mount)
        assertEquals(1.5, snap.netRxMbps)
        assertEquals(0.25, snap.netTxMbps)
        assertEquals(12.0, snap.diskReadIops)
        assertEquals(33.0, snap.diskUtilPct)
        assertEquals("nvme0n1", snap.diskBusy)
        assertEquals(12.0, snap.disks[0].readIops)
        assertEquals(33.0, snap.disks[0].utilPct)
    }

    @Test
    fun agent_version_on_the_push_is_written() = runTest {
        val repo = FakeServerRepository(listOf(server))
        IngestServerMetricsUseCase(repo, FakeServerMetricsRepository(), clock)(
            "tok",
            incoming().copy(agentVersion = "0.1.2"),
        )
        assertEquals("0.1.2", repo.find(server.id)!!.agentVersion)
    }

    @Test
    fun unknown_token_is_rejected() = runTest {
        val useCase = IngestServerMetricsUseCase(FakeServerRepository(listOf(server)), FakeServerMetricsRepository(), clock)
        assertIs<IngestServerMetricsResult.Unauthorized>(useCase("other", incoming()))
    }

    @Test
    fun claimed_server_id_must_match_the_token() = runTest {
        val useCase = IngestServerMetricsUseCase(FakeServerRepository(listOf(server)), FakeServerMetricsRepository(), clock)
        assertIs<IngestServerMetricsResult.Unauthorized>(
            useCase("tok", incoming().copy(serverId = "srv-other")),
        )
    }

    @Test
    fun removing_or_deleted_host_is_rejected() = runTest {
        val removing = IngestServerMetricsUseCase(
            FakeServerRepository(listOf(server.copy(removeStatus = "removing"))),
            FakeServerMetricsRepository(),
            clock,
        )
        assertIs<IngestServerMetricsResult.Unauthorized>(removing("tok", incoming()))

        val deleted = IngestServerMetricsUseCase(
            FakeServerRepository(listOf(server.copy(deletedAt = "2026-08-31T12:00:00Z"))),
            FakeServerMetricsRepository(),
            clock,
        )
        assertIs<IngestServerMetricsResult.Unauthorized>(deleted("tok", incoming()))
    }
}
