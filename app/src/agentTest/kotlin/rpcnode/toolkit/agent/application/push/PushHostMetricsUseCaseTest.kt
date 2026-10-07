package rpcnode.toolkit.agent.application.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.agent.application.enroll.EnrollPanelUseCase
import rpcnode.toolkit.agent.application.enroll.ProbePanel
import rpcnode.toolkit.agent.application.metrics.CollectHostMetricsUseCase
import rpcnode.toolkit.agent.application.metrics.HostMetricsSource
import rpcnode.toolkit.agent.domain.model.HostDisk
import rpcnode.toolkit.agent.domain.model.HostMetrics
import rpcnode.toolkit.agent.infrastructure.enroll.InMemoryPanelEnrollmentStore

class PushHostMetricsUseCaseTest
{
    private val snap = HostMetrics(
        cpuPct = 12.5,
        load1 = 0.4,
        loadPct = 10.0,
        ncpu = 4,
        memPct = 40.0,
        memUsedMb = 8000.0,
        memTotalMb = 16000.0,
        disks = listOf(HostDisk("nvme0n1", "/", 100.0, 500.0, 80.0)),
        os = "linux",
        arch = "amd64",
    )

    @Test
    fun does_not_post_before_enroll() = runTest {
        var posted = 0
        val push = PushHostMetricsUseCase(
            enrollment = InMemoryPanelEnrollmentStore(),
            collect = CollectHostMetricsUseCase(HostMetricsSource { snap }),
            client = PanelMetricsClient { _, _, _, _ ->
                posted += 1
                true
            },
            token = "tok",
        )
        assertFalse(push())
        assertEquals(0, posted)
    }

    @Test
    fun posts_to_panel_after_enroll() = runTest {
        val store = InMemoryPanelEnrollmentStore()
        EnrollPanelUseCase(store, ProbePanel { true })("http://10.0.0.2:8093", "srv-1")
        var url = ""
        var token = ""
        var serverId = ""
        val push = PushHostMetricsUseCase(
            enrollment = store,
            collect = CollectHostMetricsUseCase(HostMetricsSource { snap }),
            client = PanelMetricsClient { ingestUrl, tok, id, metrics ->
                url = ingestUrl
                token = tok
                serverId = id
                assertEquals(12.5, metrics.cpuPct)
                true
            },
            token = "tok",
        )
        assertTrue(push())
        assertEquals("http://10.0.0.2:8093/api/agent/v1/metrics", url)
        assertEquals("tok", token)
        assertEquals("srv-1", serverId)
    }
}
