package rpcnode.toolkit.agent.infrastructure.filesystem

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rpcnode.toolkit.agent.application.snapshot.SnapshotJobStore
import rpcnode.toolkit.agent.domain.model.SnapshotJob

class FileSnapshotJobStore(
    private val root: Path,
) : SnapshotJobStore
{
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init
    {
        Files.createDirectories(root)
    }

    override fun read(jobId: String): SnapshotJob?
    {
        val path = jobPath(jobId)
        if (!Files.isRegularFile(path))
        {
            return null
        }
        return runCatching {
            json.decodeFromString(SnapshotJobPayload.serializer(), Files.readString(path)).toDomain()
        }.getOrNull()
    }

    override fun write(job: SnapshotJob)
    {
        val path = jobPath(job.jobId)
        Files.createDirectories(path.parent)
        Files.writeString(path, json.encodeToString(job.toPayload()))
    }

    override fun isRunning(jobId: String): Boolean = read(jobId)?.running == true

    override fun list(): List<SnapshotJob>
    {
        if (!Files.isDirectory(root))
        {
            return emptyList()
        }
        return Files.list(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .map { path ->
                    runCatching {
                        json.decodeFromString(SnapshotJobPayload.serializer(), Files.readString(path)).toDomain()
                    }.getOrNull()
                }
                .toList()
                .filterNotNull()
        }
    }

    private fun jobPath(jobId: String): Path = root.resolve("${jobId.trim()}.json")
}

@Serializable
private data class SnapshotJobPayload(
    @SerialName("job_id") val jobId: String = "",
    val url: String = "",
    @SerialName("dest_dir") val destDir: String = "",
    @SerialName("stream_unpack") val streamUnpack: Boolean = false,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    val pct: Double = 0.0,
    val phase: String = "idle",
    val detail: String = "",
    val ready: Boolean = false,
    val failed: Boolean = false,
    val error: String = "",
    val running: Boolean = false,
    @SerialName("log_tail") val logTail: List<String> = emptyList(),
)

private fun SnapshotJob.toPayload() = SnapshotJobPayload(
    jobId = jobId,
    url = url,
    destDir = destDir,
    streamUnpack = streamUnpack,
    sizeBytes = sizeBytes,
    pct = pct,
    phase = phase,
    detail = detail,
    ready = ready,
    failed = failed,
    error = error,
    running = running,
    logTail = logTail,
)

private fun SnapshotJobPayload.toDomain() = SnapshotJob(
    jobId = jobId,
    url = url,
    destDir = destDir,
    streamUnpack = streamUnpack,
    sizeBytes = sizeBytes,
    pct = pct,
    phase = phase,
    detail = detail,
    ready = ready,
    failed = failed,
    error = error,
    running = running,
    logTail = logTail,
)
