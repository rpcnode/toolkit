package rpcnode.toolkit.agent.application.node

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import rpcnode.toolkit.agent.domain.model.RunningNode

class GetNodeProcessLogsUseCaseTest
{
    @Test
    fun readTailLines_returns_last_n()
    {
        val dir = Files.createTempDirectory("node-logs-test")
        val file = dir.resolve("node.out")
        Files.writeString(file, (1..20).joinToString("\n") { "line-$it" } + "\n")
        val (lines, truncated) = readTailLines(file, maxLines = 5)
        assertEquals(listOf("line-16", "line-17", "line-18", "line-19", "line-20"), lines)
        assertTrue(truncated)
    }

    @Test
    fun readTailLines_empty_file()
    {
        val dir = Files.createTempDirectory("node-logs-empty")
        val file = dir.resolve("node.out")
        Files.writeString(file, "")
        val (lines, truncated) = readTailLines(file, maxLines = 10)
        assertEquals(emptyList(), lines)
        assertFalse(truncated)
    }

    @Test
    fun uses_catalog_log_file_under_node_dir()
    {
        val nodeDir = Files.createTempDirectory("tron-node")
        val logs = Files.createDirectories(nodeDir.resolve("logs"))
        Files.writeString(logs.resolve("tron.log"), "hello from tron\nsecond\n")
        val registry = object : RunningNodeRegistry
        {
            override fun upsert(node: RunningNode) {}
            override fun remove(nodeId: String) {}
            override fun get(nodeId: String): RunningNode? = null
            override fun list(): List<RunningNode> = emptyList()
        }
        val result = GetNodeProcessLogsUseCase(registry)(
            nodeIdRaw = "node-1",
            linesRaw = 50,
            nodeDirRaw = nodeDir.toString(),
            logFileRaw = "logs/tron.log",
        )
        val ok = assertIs<GetNodeProcessLogsResult.Ok>(result)
        assertEquals(listOf("hello from tron", "second"), ok.view.lines)
        assertTrue(ok.view.path.endsWith("logs/tron.log"))
    }
}
