package rpcnode.toolkit.nodes.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import rpcnode.toolkit.nodes.application.disks.HostDiskReader
import rpcnode.toolkit.nodes.domain.model.HostBlockDevice
import rpcnode.toolkit.nodes.domain.model.HostDiskCatalog
import rpcnode.toolkit.nodes.domain.model.HostMount
import rpcnode.toolkit.shared.infrastructure.log.HttpIoLog

class HttpHostDiskReader(
    private val timeout: Duration = Duration.ofSeconds(15),
) : HostDiskReader
{
    private val log = LoggerFactory.getLogger(HttpHostDiskReader::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun read(agentUrl: String, token: String): HostDiskCatalog? = withContext(Dispatchers.IO) {
        try
        {
            val url = "${agentUrl.trimEnd('/')}/api/v1/host/disks"
            val started = System.nanoTime()
            val client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build()
            val req = HttpRequest.newBuilder(URI(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $token")
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            HttpIoLog.outbound("GET", url, resp.statusCode(), (System.nanoTime() - started) / 1_000_000)
            if (resp.statusCode() !in 200 until 300)
            {
                return@withContext null
            }
            val body = json.decodeFromString(AgentHostDisksPayload.serializer(), resp.body())
            HostDiskCatalog(
                disks = body.disks.map { it.toDomain() },
                mounts = body.mounts.map { it.toDomain() },
                unused = body.unused.map { it.toDomain() },
            )
        }
        catch (e: Exception)
        {
            val reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
            log.warn("host disks {}: {}", agentUrl, reason)
            null
        }
    }
}

@Serializable
private data class AgentHostDisksPayload(
    val ok: Boolean = true,
    val disks: List<AgentHostDiskItemPayload> = emptyList(),
    val mounts: List<AgentHostMountItemPayload> = emptyList(),
    val unused: List<AgentHostDiskItemPayload> = emptyList(),
)

@Serializable
private data class AgentHostDiskItemPayload(
    val name: String = "",
    val path: String = "",
    val model: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("size_human") val sizeHuman: String = "",
    val tran: String = "",
    val rota: Boolean = false,
    val type: String = "",
    val mountpoint: String = "",
    val fstype: String = "",
    @SerialName("fsavail_bytes") val fsavailBytes: Long = 0,
    @SerialName("fsused_pct") val fsusedPct: Double = 0.0,
    val preferred: Boolean = false,
    @SerialName("planned_mount") val plannedMount: String = "",
)

@Serializable
private data class AgentHostMountItemPayload(
    val target: String = "",
    val source: String = "",
    val fstype: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("avail_bytes") val availBytes: Long = 0,
    @SerialName("avail_human") val availHuman: String = "",
    @SerialName("used_pct") val usedPct: Double = 0.0,
    @SerialName("disk_name") val diskName: String = "",
    @SerialName("disk_path") val diskPath: String = "",
    val tran: String = "",
    val rota: Boolean = false,
    val preferred: Boolean = false,
)

private fun AgentHostDiskItemPayload.toDomain() = HostBlockDevice(
    name = name,
    path = path,
    model = model,
    sizeBytes = sizeBytes,
    sizeHuman = sizeHuman,
    tran = tran,
    rota = rota,
    type = type,
    mountpoint = mountpoint,
    fstype = fstype,
    fsavailBytes = fsavailBytes,
    fsusedPct = fsusedPct,
    preferred = preferred,
    plannedMount = plannedMount,
)

private fun AgentHostMountItemPayload.toDomain() = HostMount(
    target = target,
    source = source,
    fstype = fstype,
    sizeBytes = sizeBytes,
    availBytes = availBytes,
    availHuman = availHuman,
    usedPct = usedPct,
    diskName = diskName,
    diskPath = diskPath,
    tran = tran,
    rota = rota,
    preferred = preferred,
)
