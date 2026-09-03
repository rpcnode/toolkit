package rpcnode.toolkit.cdn.presentation

import java.nio.file.Files
import java.nio.file.Path

/** KEY=value lines. Unknown keys ignored. */
data class CdnEnvValues(
    val snapshotDir: String? = null,
    val pollSec: String? = null,
    val downloadJobs: String? = null,
    val targetsFile: String? = null,
    val publicOrigin: String? = null,
)

object CdnEnvFile
{
    fun parse(text: String): CdnEnvValues
    {
        var snapshotDir: String? = null
        var pollSec: String? = null
        var downloadJobs: String? = null
        var targetsFile: String? = null
        var publicOrigin: String? = null
        for (raw in text.lineSequence())
        {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#"))
            {
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0)
            {
                continue
            }
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim().trim('"', '\'')
            when (key)
            {
                "SNAPSHOT_CDN_DIR" -> snapshotDir = value.ifEmpty { null }
                "CDN_POLL_SEC" -> pollSec = value.ifEmpty { null }
                "CDN_DOWNLOAD_JOBS" -> downloadJobs = value.ifEmpty { null }
                "CDN_TARGETS_FILE" -> targetsFile = value.ifEmpty { null }
                "CDN_PUBLIC_ORIGIN" -> publicOrigin = value.ifEmpty { null }
            }
        }
        return CdnEnvValues(
            snapshotDir = snapshotDir,
            pollSec = pollSec,
            downloadJobs = downloadJobs,
            targetsFile = targetsFile,
            publicOrigin = publicOrigin,
        )
    }

    fun read(path: Path): CdnEnvValues?
    {
        if (!Files.isRegularFile(path))
        {
            return null
        }
        return parse(Files.readString(path))
    }

    fun write(path: Path, values: CdnEnvValues)
    {
        val parent = path.parent
        if (parent != null)
        {
            Files.createDirectories(parent)
        }
        val body = buildString {
            values.snapshotDir?.let { append("SNAPSHOT_CDN_DIR=").append(it).append('\n') }
            values.pollSec?.let { append("CDN_POLL_SEC=").append(it).append('\n') }
            values.downloadJobs?.let { append("CDN_DOWNLOAD_JOBS=").append(it).append('\n') }
            values.targetsFile?.let { append("CDN_TARGETS_FILE=").append(it).append('\n') }
            values.publicOrigin?.let { append("CDN_PUBLIC_ORIGIN=").append(it).append('\n') }
        }
        Files.writeString(path, body)
    }
}
