package rpcnode.toolkit.nodes.application.snapshot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

class GetNodeSnapshotProgressUseCaseTest
{
    private val nodeId = NodeId.parse("44444444-4444-4444-8444-444444444444")!!
    private val serverId = ServerId.parse("srv-1")!!
    private val now = "2026-10-08T12:00:00Z"

    private fun useCase(
        status: String,
        updatedAt: String,
        host: SnapshotHostProgress,
    ): Pair<FakeNodeRepository, GetNodeSnapshotProgressUseCase>
    {
        val node = Node(
            id = nodeId,
            serverId = serverId,
            name = "TRON mainnet",
            network = NetworkId.TRON,
            env = EnvId.MAINNET,
            status = NodeStatus.parse(status),
            createdAt = "t",
            updatedAt = updatedAt,
        )
        val server = Server(id = serverId, name = "box", agentUrl = "http://127.0.0.1:9", agentKey = "tok", createdAt = "t", updatedAt = "t")
        val nodes = FakeNodeRepository(listOf(node))
        return nodes to GetNodeSnapshotProgressUseCase(
            nodes = nodes,
            servers = FakeServerRepository(listOf(server)),
            pollHost = PollSnapshotOnHost { _, _, _ -> host },
            clock = { now },
        )
    }

    private val noJob = SnapshotHostProgress(ok = false, phase = "idle", detail = "No snapshot job yet")

    @Test
    fun a_running_snapshot_the_host_forgot_becomes_a_restartable_error()
    {
        runTest {
            val (nodes, uc) = useCase("snapshot_running", updatedAt = "2026-10-08T11:00:00Z", host = noJob)
            val r = uc(nodeId.value) as NodeSnapshotProgressResult.Ok
            assertTrue(r.failed)
            assertEquals("failed", r.phase)
            assertEquals("snapshot_error", r.status)
            assertTrue(r.error.contains("start the snapshot again"), r.error)
            assertEquals("snapshot_error", nodes.findById(nodeId)!!.status.value)
        }
    }

    @Test
    fun a_job_that_was_started_seconds_ago_gets_time_to_appear()
    {
        runTest {
            val (nodes, uc) = useCase("snapshot_running", updatedAt = "2026-10-08T11:59:50Z", host = noJob)
            val r = uc(nodeId.value) as NodeSnapshotProgressResult.Ok
            assertFalse(r.failed)
            assertEquals("snapshot_running", r.status)
            assertEquals("snapshot_running", nodes.findById(nodeId)!!.status.value)
        }
    }

    @Test
    fun idle_means_nothing_for_nodes_that_are_not_downloading()
    {
        runTest {
            val (nodes, uc) = useCase("snapshot_complete", updatedAt = "2026-10-08T01:00:00Z", host = noJob)
            val r = uc(nodeId.value) as NodeSnapshotProgressResult.Ok
            assertFalse(r.failed)
            assertEquals("snapshot_complete", nodes.findById(nodeId)!!.status.value)
        }
    }

    @Test
    fun a_normal_running_job_is_untouched()
    {
        runTest {
            val host = SnapshotHostProgress(ok = true, pct = 41.0, phase = "download", detail = "Streaming…")
            val (_, uc) = useCase("snapshot_running", updatedAt = "2026-10-08T01:00:00Z", host = host)
            val r = uc(nodeId.value) as NodeSnapshotProgressResult.Ok
            assertFalse(r.failed)
            assertEquals(41.0, r.pct)
            assertEquals("snapshot_running", r.status)
        }
    }
}
