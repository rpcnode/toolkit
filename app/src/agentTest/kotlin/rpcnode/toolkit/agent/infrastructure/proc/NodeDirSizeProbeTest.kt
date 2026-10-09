package rpcnode.toolkit.agent.infrastructure.proc

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeDirSizeProbeTest
{
    @Test
    fun measures_directory_bytes()
    {
        val dir = Files.createTempDirectory("node-dir-size")
        try
        {
            Files.writeString(dir.resolve("a.bin"), "hello")
            Files.createDirectory(dir.resolve("sub"))
            Files.writeString(dir.resolve("sub/b.bin"), "world!!")
            val bytes = NodeDirSizeProbe(ttlMs = 0).sizeBytes(dir.toString())
            assertTrue(bytes >= 12, "expected at least hello+world!! bytes, got $bytes")
            // Cached path still works after rewrite with ttl > 0
            val cached = NodeDirSizeProbe(ttlMs = 60_000).sizeBytes(dir.toString())
            assertTrue(cached >= 12)
        }
        finally
        {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejects_unsafe_or_missing_paths()
    {
        val probe = NodeDirSizeProbe()
        assertEquals(-1, probe.sizeBytes(""))
        assertEquals(-1, probe.sizeBytes("relative"))
        assertEquals(-1, probe.sizeBytes("/tmp/../etc"))
        assertEquals(-1, probe.sizeBytes("/no/such/node/dir-${System.nanoTime()}"))
    }
}
