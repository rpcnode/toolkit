package rpcnode.toolkit.agent.application.node

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import rpcnode.toolkit.agent.domain.model.RunningNode
import rpcnode.toolkit.agent.infrastructure.enroll.InMemoryPanelEnrollmentStore

class GetNodeClientVersionUseCaseTest
{
    private class FakeRegistry(
        private val nodes: Map<String, RunningNode> = emptyMap(),
    ) : RunningNodeRegistry
    {
        override fun upsert(node: RunningNode) {}
        override fun remove(nodeId: String) {}
        override fun get(nodeId: String): RunningNode? = nodes[nodeId]
        override fun list(): List<RunningNode> = nodes.values.toList()
    }

    private fun useCase(registry: RunningNodeRegistry = FakeRegistry()) =
        GetNodeClientVersionUseCase(registry, InMemoryPanelEnrollmentStore())

    private fun read(
        registry: RunningNodeRegistry = FakeRegistry(),
        nodeId: String,
        nodeDir: String?,
        seed: String?,
    ) = runBlocking { useCase(registry)(nodeId, nodeDir, seed) }

    @Test
    fun reads_version_from_disk()
    {
        val dir = Files.createTempDirectory("node-client-ver")
        Files.writeString(dir.resolve("VERSION"), "GreatVoyage-v1\n")
        val registry = FakeRegistry(
            mapOf(
                "n1" to RunningNode(
                    nodeId = "n1",
                    network = "tron",
                    env = "nile",
                    nodeDir = dir.toString(),
                    httpPort = 8090,
                    pid = 42,
                ),
            ),
        )
        val got = read(registry, "n1", null, null)
        assertEquals(
            GetNodeClientVersionResult.Ok(
                NodeClientVersionView(
                    nodeId = "n1",
                    clientVersion = "GreatVoyage-v1",
                    path = dir.resolve("VERSION").toAbsolutePath().toString(),
                ),
            ),
            got,
        )
    }

    @Test
    fun prefers_registry_node_dir_over_panel_hint()
    {
        val registryDir = Files.createTempDirectory("node-client-ver-reg")
        Files.writeString(registryDir.resolve("VERSION"), "from-registry\n")
        val panelDir = Files.createTempDirectory("node-client-ver-panel")
        val registry = FakeRegistry(
            mapOf(
                "n1" to RunningNode(
                    nodeId = "n1",
                    network = "tron",
                    env = "nile",
                    nodeDir = registryDir.toString(),
                    httpPort = 8090,
                    pid = 42,
                ),
            ),
        )
        val got = read(registry, "n1", panelDir.toString(), null)
        assertEquals("from-registry", (got as GetNodeClientVersionResult.Ok).view.clientVersion)
    }

    @Test
    fun writes_seed_when_disk_missing()
    {
        val dir = Files.createTempDirectory("node-client-ver-seed")
        val got = read(nodeId = "n2", nodeDir = dir.toString(), seed = "seeded-v1")
        assertEquals("seeded-v1", (got as GetNodeClientVersionResult.Ok).view.clientVersion)
        assertEquals("seeded-v1", Files.readString(dir.resolve("VERSION")).trim())
    }

    @Test
    fun accepts_explicit_node_dir()
    {
        val dir = Files.createTempDirectory("node-client-ver-dir")
        Files.writeString(dir.resolve("VERSION"), "from-dir\n")
        val got = read(nodeId = "n2", nodeDir = dir.toString(), seed = null)
        assertEquals("from-dir", (got as GetNodeClientVersionResult.Ok).view.clientVersion)
    }

    @Test
    fun not_found_without_node_dir()
    {
        assertEquals(
            GetNodeClientVersionResult.NotFound,
            read(nodeId = "missing", nodeDir = null, seed = null),
        )
    }

    @Test
    fun always_returns_version_path()
    {
        val dir = Files.createTempDirectory("node-client-ver-path")
        val got = read(nodeId = "n3", nodeDir = dir.toString(), seed = null)
        assertEquals(
            dir.resolve("VERSION").toAbsolutePath().toString(),
            (got as GetNodeClientVersionResult.Ok).view.path,
        )
    }
}
