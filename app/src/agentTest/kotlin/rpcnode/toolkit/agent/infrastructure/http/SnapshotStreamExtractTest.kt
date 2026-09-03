package rpcnode.toolkit.agent.infrastructure.http

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotStreamExtractTest
{
    @Test
    fun flatten_moves_nested_output_directory_inside_dest()
    {
        val dest = Files.createTempDirectory("snap-dest")
        val nested = dest.resolve("output-directory")
        Files.createDirectories(nested.resolve("database"))
        Files.writeString(nested.resolve("database").resolve("x.db"), "data")

        SnapshotStreamExtract().flattenNestedOutputDirectory(dest)

        assertTrue(Files.isRegularFile(dest.resolve("database").resolve("x.db")))
        assertFalse(Files.exists(nested))
        assertEquals("data", Files.readString(dest.resolve("database").resolve("x.db")))
    }
}
