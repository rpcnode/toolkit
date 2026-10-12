package rpcnode.toolkit.nodes.infrastructure.host

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Last [bytes] of a log file without loading it: node logs reach many GB (a Solana validator.log killed the agent's
 * height push with «OutOfMemoryError: Required array size too large» when it was read whole).
 */
object LogTail
{
    fun read(file: Path, bytes: Int = 512 * 1024): String?
    {
        if (!Files.isRegularFile(file))
        {
            return null
        }
        return runCatching {
            RandomAccessFile(file.toFile(), "r").use { raf ->
                val size = raf.length()
                val from = (size - bytes).coerceAtLeast(0)
                raf.seek(from)
                val buf = ByteArray((size - from).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        }.getOrNull()
    }
}
