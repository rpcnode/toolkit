package rpcnode.toolkit.nodes.application.remove

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

class NodeRemovalStepsTest
{
    private val nodeId = NodeId.parse("55555555-5555-4555-8555-555555555555")!!
    private val serverId = ServerId.parse("srv-1")!!

    private val node = Node(
        id = nodeId,
        serverId = serverId,
        name = "TRON nile",
        network = NetworkId.TRON,
        env = EnvId.parse("nile")!!,
        status = NodeStatus.parse("running"),
        createdAt = "t",
        updatedAt = "t",
    )
    private val server = Server(id = serverId, name = "box", agentUrl = "http://127.0.0.1:9", agentKey = "tok", createdAt = "t", updatedAt = "t")

    private fun view(done: Boolean, failed: Boolean = false) = NodeRemovalView(
        nodeId = nodeId.value,
        steps = listOf(RemovalStepView("stop", "Stop the node", if (done) "done" else "running", "")),
        done = done,
        failed = failed,
        error = if (failed) "boom" else "",
    )

    private class Host(var next: RemovalOnHostResult?) : NodeRemovalOnHost
    {
        var started: StartRemovalOnHostCommand? = null
        override suspend fun start(agentUrl: String, token: String, command: StartRemovalOnHostCommand): RemovalOnHostResult?
        {
            started = command
            return next
        }

        override suspend fun progress(agentUrl: String, token: String, nodeId: String) = next
    }

    private fun steps(host: Host, repo: FakeNodeRepository = FakeNodeRepository(listOf(node))) =
        repo to NodeRemovalSteps(
            nodes = repo,
            servers = FakeServerRepository(listOf(server)),
            resolveDestDir = { "/data/tron/nile/litefullnode" },
            host = host,
        )

    @Test
    fun panel_mode_drops_only_the_row()
    {
        runTest {
            val host = Host(null)
            val (repo, uc) = steps(host)
            val r = uc.start(nodeId.value, RemoveNodeMode.PANEL) as NodeRemovalResult.Ok
            assertTrue(r.view.done)
            assertNull(repo.findById(nodeId))
            assertNull(host.started, "the host is not contacted")
        }
    }

    @Test
    fun wipe_sends_every_folder_the_node_can_live_in_and_keeps_the_row_while_running()
    {
        runTest {
            val host = Host(RemovalOnHostResult.Ok(view(done = false)))
            val (repo, uc) = steps(host)
            val r = uc.start(nodeId.value, RemoveNodeMode.WIPE) as NodeRemovalResult.Ok
            assertEquals(false, r.view.done)
            assertNotNull(repo.findById(nodeId))
            val cmd = host.started!!
            assertTrue(cmd.wipeData)
            assertEquals("tron", cmd.network)
            assertEquals("nile", cmd.env)
            assertTrue("/data/tron/nile/litefullnode" in cmd.dirs, cmd.dirs.toString())
            assertTrue("/data/tron/nile/fullnode" in cmd.dirs, "Full leaf is cleaned too: ${cmd.dirs}")
        }
    }

    @Test
    fun agents_mode_keeps_the_chain_data()
    {
        runTest {
            val host = Host(RemovalOnHostResult.Ok(view(done = false)))
            val (_, uc) = steps(host)
            uc.start(nodeId.value, RemoveNodeMode.AGENTS)
            assertEquals(false, host.started!!.wipeData)
        }
    }

    @Test
    fun the_row_is_deleted_only_when_the_host_reports_done()
    {
        runTest {
            val host = Host(RemovalOnHostResult.Ok(view(done = false)))
            val (repo, uc) = steps(host)
            uc.progress(nodeId.value)
            assertNotNull(repo.findById(nodeId))

            host.next = RemovalOnHostResult.Ok(view(done = false, failed = true))
            val failed = uc.progress(nodeId.value) as NodeRemovalResult.Ok
            assertTrue(failed.view.failed)
            assertNotNull(repo.findById(nodeId), "a failed removal can be retried")

            host.next = RemovalOnHostResult.Ok(view(done = true))
            uc.progress(nodeId.value)
            assertNull(repo.findById(nodeId))
        }
    }

    @Test
    fun host_problems_map_to_clear_results()
    {
        runTest {
            val host = Host(null)
            val (_, uc) = steps(host)
            assertIs<NodeRemovalResult.AgentUnreachable>(uc.progress(nodeId.value))

            host.next = RemovalOnHostResult.Failed("invalid_agent_key", "x")
            assertIs<NodeRemovalResult.InvalidAgentKey>(uc.progress(nodeId.value))

            host.next = RemovalOnHostResult.NoJob
            assertEquals("no_removal_job", (uc.progress(nodeId.value) as NodeRemovalResult.Failed).error)

            assertIs<NodeRemovalResult.NotFound>(uc.progress("nope"))
        }
    }
}
