package rpcnode.toolkit.cdn.infrastructure.filesystem

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.cdn.infrastructure.http.ResumableHttpDownload

data class MountPoint(
    val path: Path,
    val filesystem: String,
    val sizeBytes: Long,
    val freeBytes: Long,
)
{
    val label: String
        get()
        {
            val free = ResumableHttpDownload.formatBytes(freeBytes)
            val size = ResumableHttpDownload.formatBytes(sizeBytes)
            val fs = filesystem.substringAfterLast('/').ifEmpty { filesystem }
            return "$path  ·  $free free / $size  ·  $fs"
        }
}

/** Mounts suitable for large snapshot storage (`df`). */
object CdnMountLister
{
    fun list(run: (List<String>) -> String? = ::runDf): List<MountPoint>
    {
        val text = run(
            listOf(
                "df", "-B1", "-P",
                "-x", "tmpfs",
                "-x", "devtmpfs",
                "-x", "squashfs",
                "-x", "overlay",
                "-x", "efivarfs",
            ),
        ) ?: return emptyList()
        return parseDf(text)
            .filter { Files.isDirectory(it.path) }
            .filter { it.path.toString() != "/boot" && !it.path.toString().startsWith("/boot/") }
            .sortedByDescending { it.freeBytes }
    }

    fun parseDf(text: String): List<MountPoint>
    {
        val out = mutableListOf<MountPoint>()
        for (raw in text.lineSequence().drop(1))
        {
            val line = raw.trim()
            if (line.isEmpty())
            {
                continue
            }
            // POSIX df -P: Filesystem 1B-blocks Used Available Capacity Mounted on
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 6)
            {
                continue
            }
            val filesystem = parts[0]
            val size = parts[1].toLongOrNull() ?: continue
            val free = parts[3].toLongOrNull() ?: continue
            val mount = parts.drop(5).joinToString(" ")
            if (mount.isBlank())
            {
                continue
            }
            out += MountPoint(
                path = Path.of(mount),
                filesystem = filesystem,
                sizeBytes = size,
                freeBytes = free,
            )
        }
        return out
    }

    private fun runDf(cmd: List<String>): String?
    {
        return try
        {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val body = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) null else body
        }
        catch (_: Exception)
        {
            null
        }
    }
}
