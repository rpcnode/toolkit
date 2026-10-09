package rpcnode.toolkit.agent.infrastructure.filesystem

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Crash-safe replacement for `Files.writeString`.
 *
 * `Files.writeString` truncates the file first and writes after. A power cut or hard reboot in
 * between leaves an EMPTY file — a snapshot job, the panel enrollment or the running-nodes list
 * then simply vanishes. Here the new content goes to a temp file in the same directory, is
 * flushed to disk, and replaces the target with one atomic rename: after a crash the file holds
 * either the old content or the new one, never half of it.
 */
object AtomicFile
{
    fun writeString(path: Path, text: String, keepPermissions: Boolean = false)
    {
        writeBytes(path, text.toByteArray(Charsets.UTF_8), keepPermissions)
    }

    /** [keepPermissions]: reuse the mode of the file being replaced (config files), else owner-only. */
    fun writeBytes(path: Path, bytes: ByteArray, keepPermissions: Boolean = false)
    {
        val dir = path.toAbsolutePath().parent
        Files.createDirectories(dir)
        val previousMode: Set<PosixFilePermission>? =
            if (keepPermissions) runCatching { Files.getPosixFilePermissions(path) }.getOrNull() else null
        val tmp = Files.createTempFile(dir, ".${path.fileName}.", ".tmp")
        try
        {
            FileChannel.open(tmp, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining())
                {
                    channel.write(buffer)
                }
                channel.force(true)
            }
            if (previousMode != null)
            {
                runCatching { Files.setPosixFilePermissions(tmp, previousMode) }
            }
            try
            {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            catch (_: AtomicMoveNotSupportedException)
            {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        finally
        {
            Files.deleteIfExists(tmp)
        }
    }
}
