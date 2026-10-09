package rpcnode.toolkit.agent.application.enroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.agent.domain.model.PanelEnrollment
import rpcnode.toolkit.agent.infrastructure.enroll.InMemoryPanelEnrollmentStore

class EnrollPanelUseCaseTest
{
    @Test
    fun stores_panel_url_and_server_id() = runTest {
        val store = InMemoryPanelEnrollmentStore()
        val ok = assertIs<EnrollPanelResult.Ok>(
            EnrollPanelUseCase(store, ProbePanel { true })("http://10.0.0.2:8093/", "srv-1", ""),
        )
        assertEquals("http://10.0.0.2:8093", ok.enrollment.panelUrl)
        assertEquals("srv-1", ok.enrollment.serverId)
        assertEquals(PanelEnrollment.DEFAULT_INGEST_PATH, ok.enrollment.ingestPath)
        assertEquals("http://10.0.0.2:8093/api/agent/v1/metrics", store.read()!!.ingestUrl())
    }

    @Test
    fun blank_panel_or_server_is_rejected() = runTest {
        val useCase = EnrollPanelUseCase(InMemoryPanelEnrollmentStore(), ProbePanel { error("should not probe") })
        assertIs<EnrollPanelResult.MissingPanelUrl>(useCase("  ", "srv-1"))
        assertIs<EnrollPanelResult.MissingServerId>(useCase("http://127.0.0.1:8093", " "))
    }

    @Test
    fun store_write_failure_is_not_ok() = runTest {
        val store = object : PanelEnrollmentStore
        {
            override suspend fun read(): PanelEnrollment? = null
            override suspend fun write(enrollment: PanelEnrollment)
            {
                error("access denied")
            }
            override suspend fun clear() = Unit
        }
        val failed = assertIs<EnrollPanelResult.StoreFailed>(
            EnrollPanelUseCase(store, ProbePanel { true })("http://10.0.0.2:8093", "srv-1"),
        )
        assertEquals("access denied", failed.detail)
    }

    @Test
    fun unreachable_panel_is_not_stored() = runTest {
        val store = InMemoryPanelEnrollmentStore()
        val useCase = EnrollPanelUseCase(store, ProbePanel { false })
        assertIs<EnrollPanelResult.PanelUnreachable>(useCase("http://10.0.0.2:8093", "srv-1"))
        assertEquals(null, store.read())
    }
}
