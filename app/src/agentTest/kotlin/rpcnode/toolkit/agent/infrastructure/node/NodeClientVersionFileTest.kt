package rpcnode.toolkit.agent.infrastructure.node

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeClientVersionFileTest
{
    @Test
    fun resolve_prefers_disk_over_seed()
    {
        val dir = Files.createTempDirectory("node-ver")
        Files.writeString(dir.resolve("VERSION"), "from-disk\n")
        assertEquals("from-disk", resolveHostClientVersion(dir.toString(), seed = "from-seed"))
    }

    @Test
    fun resolve_writes_seed_when_disk_missing()
    {
        val dir = Files.createTempDirectory("node-ver-seed")
        val got = resolveHostClientVersion(dir.toString(), seed = "seeded-v1")
        assertEquals("seeded-v1", got)
        assertTrue(Files.isRegularFile(dir.resolve("VERSION")))
        assertEquals("seeded-v1", readNodeClientVersion(dir.toString()))
    }
}
