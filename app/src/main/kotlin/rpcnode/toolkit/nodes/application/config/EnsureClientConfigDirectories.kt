package rpcnode.toolkit.nodes.application.config

import java.nio.file.Files
import java.nio.file.Path

/** Creates absolute paths referenced by rendered client config (datadir, blocksdir, …). */
object EnsureClientConfigDirectories
{
    fun create(assignments: Map<String, String>)
    {
        for (raw in assignments.values)
        {
            val path = raw.trim()
            if (path.isEmpty() || !path.startsWith("/") || path.contains(".."))
            {
                continue
            }
            Files.createDirectories(Path.of(path))
        }
    }
}
