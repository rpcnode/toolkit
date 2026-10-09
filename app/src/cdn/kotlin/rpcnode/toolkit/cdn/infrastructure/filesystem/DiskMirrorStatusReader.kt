package rpcnode.toolkit.cdn.infrastructure.filesystem

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rpcnode.toolkit.cdn.application.status.MirrorState
import rpcnode.toolkit.cdn.application.status.MirrorStatusReader
import rpcnode.toolkit.cdn.application.status.MirrorStatusRow
import rpcnode.toolkit.cdn.application.sync.SnapshotTarget

/**
 * Reads per-target disk state for the menu / `status` command:
 * VERSION + manifest, optional `progress.json` + `*.tmp` while sync downloads.
 */
class DiskMirrorStatusReader(
    private val root: Path,
) : MirrorStatusReader
{
    private val json = Json { ignoreUnknownKeys = true }

    override fun status(targets: List<SnapshotTarget>): List<MirrorStatusRow> =
        targets.map { readOne(it) }

    private fun readOne(target: SnapshotTarget): MirrorStatusRow
    {
        val dir = root.resolve("snapshots")
            .resolve(target.network)
            .resolve(target.env)
            .resolve(target.type)
        if (!Files.isDirectory(dir))
        {
            return empty(target)
        }
        val onDiskVersion = readVersion(dir)
        val manifest = readManifest(dir)
        val progress = readProgress(dir)
        val tmp = findTmp(dir, progress?.filename ?: manifest?.filename)
        val haveTmp = tmp != null && Files.isRegularFile(tmp)
        val downloading = progress != null || haveTmp
        if (downloading)
        {
            val filename = progress?.filename ?: tmp?.fileName?.toString()?.removeSuffix(".tmp")
            val total = progress?.sizeBytes ?: manifest?.sizeBytes
            val have = tmp?.let { runCatching { Files.size(it) }.getOrNull() }
            return MirrorStatusRow(
                target = target,
                state = MirrorState.DOWNLOADING,
                onDiskVersion = onDiskVersion,
                fetchingVersion = progress?.version,
                filename = filename,
                haveBytes = have,
                totalBytes = total,
                downloadCount = readDownloadCount(dir),
            )
        }
        if (onDiskVersion != null)
        {
            val archive = manifest?.filename?.let { dir.resolve(it) }
                ?.takeIf { Files.isRegularFile(it) }
            val size = manifest?.sizeBytes
                ?: archive?.let { runCatching { Files.size(it) }.getOrNull() }
            return MirrorStatusRow(
                target = target,
                state = MirrorState.READY,
                onDiskVersion = onDiskVersion,
                fetchingVersion = null,
                filename = manifest?.filename,
                haveBytes = size,
                totalBytes = size,
                downloadCount = readDownloadCount(dir),
            )
        }
        return empty(target)
    }

    private fun empty(target: SnapshotTarget) = MirrorStatusRow(
        target = target,
        state = MirrorState.EMPTY,
        onDiskVersion = null,
        fetchingVersion = null,
        filename = null,
        haveBytes = null,
        totalBytes = null,
        downloadCount = 0L,
    )

    private fun readDownloadCount(dir: Path): Long
    {
        val file = dir.resolve("downloads.json")
        if (!Files.isRegularFile(file))
        {
            return 0L
        }
        return runCatching {
            json.decodeFromString<DownloadsLite>(Files.readString(file)).count.coerceAtLeast(0L)
        }.getOrDefault(0L)
    }

    private fun readVersion(dir: Path): String?
    {
        val file = dir.resolve("VERSION")
        if (!Files.isRegularFile(file))
        {
            return null
        }
        return Files.readString(file).trim().ifEmpty { null }
    }

    private fun readManifest(dir: Path): ManifestLite?
    {
        val file = dir.resolve("manifest.json")
        if (!Files.isRegularFile(file))
        {
            return null
        }
        return runCatching { json.decodeFromString<ManifestLite>(Files.readString(file)) }.getOrNull()
    }

    private fun readProgress(dir: Path): ProgressLite?
    {
        val file = dir.resolve("progress.json")
        if (!Files.isRegularFile(file))
        {
            return null
        }
        return runCatching { json.decodeFromString<ProgressLite>(Files.readString(file)) }.getOrNull()
    }

    private fun findTmp(dir: Path, preferredFilename: String?): Path?
    {
        if (preferredFilename != null)
        {
            val named = dir.resolve("$preferredFilename.tmp")
            if (Files.isRegularFile(named))
            {
                return named
            }
        }
        if (!Files.isDirectory(dir))
        {
            return null
        }
        Files.list(dir).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".tmp") }
                .findFirst()
                .orElse(null)
        }
    }

    @Serializable
    private data class ManifestLite(
        val filename: String = "",
        @SerialName("size_bytes") val sizeBytes: Long? = null,
    )

    @Serializable
    private data class ProgressLite(
        val version: String,
        val filename: String,
        @SerialName("size_bytes") val sizeBytes: Long? = null,
    )

    @Serializable
    private data class DownloadsLite(
        val count: Long = 0,
    )
}
