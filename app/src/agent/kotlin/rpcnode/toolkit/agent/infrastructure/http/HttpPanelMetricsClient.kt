package rpcnode.toolkit.agent.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import rpcnode.toolkit.agent.application.push.PanelMetricsClient
import rpcnode.toolkit.agent.domain.model.HostMetrics

class HttpPanelMetricsClient(
    private val timeout: Duration = Duration.ofSeconds(12),
    /** IDEA / `RPCNODE_DEV`: log each push to the panel (no token). */
    private val logRequests: Boolean = false,
    private val agentVersion: String = "",
) : PanelMetricsClient
{
    private val log = LoggerFactory.getLogger(HttpPanelMetricsClient::class.java)
    private val json = Json { encodeDefaults = true }

    override suspend fun post(ingestUrl: String, token: String, serverId: String, metrics: HostMetrics): Boolean =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            try
            {
                val body = json.encodeToString(metrics.toBody(serverId, agentVersion))
                val client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(timeout)
                    .build()
                val req = HttpRequest.newBuilder(URI(ingestUrl))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer $token")
                    .header("X-Api-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
                val ms = Duration.ofNanos(System.nanoTime() - started).toMillis()
                if (resp.statusCode() !in 200 until 300)
                {
                    log.warn("POST {} → {} {}ms", ingestUrl, resp.statusCode(), ms)
                    return@withContext false
                }
                if (logRequests)
                {
                    log.info(
                        "POST {} → {} {}ms cpu={} mem={} load={} disks={}",
                        ingestUrl,
                        resp.statusCode(),
                        ms,
                        metrics.cpuPct,
                        metrics.memPct,
                        metrics.load1,
                        metrics.disks.size,
                    )
                }
                true
            }
            catch (e: Exception)
            {
                val ms = Duration.ofNanos(System.nanoTime() - started).toMillis()
                log.warn("POST {} → fail {}ms: {}", ingestUrl, ms, e.message)
                false
            }
        }
}

@Serializable
private data class PushDiskBody(
    val name: String,
    val mount: String,
    @SerialName("free_gb") val freeGb: Double,
    @SerialName("total_gb") val totalGb: Double,
    @SerialName("used_pct") val usedPct: Double,
    @SerialName("read_iops") val readIops: Double = 0.0,
    @SerialName("write_iops") val writeIops: Double = 0.0,
    @SerialName("read_mb_s") val readMbS: Double = 0.0,
    @SerialName("write_mb_s") val writeMbS: Double = 0.0,
    @SerialName("util_pct") val utilPct: Double = 0.0,
)

@Serializable
private data class PushMetricsBody(
    @SerialName("server_id") val serverId: String,
    @SerialName("cpu_pct") val cpuPct: Double,
    @SerialName("load_1") val load1: Double,
    @SerialName("load_pct") val loadPct: Double,
    val ncpu: Int,
    @SerialName("mem_pct") val memPct: Double,
    @SerialName("mem_used_mb") val memUsedMb: Double,
    @SerialName("mem_total_mb") val memTotalMb: Double,
    @SerialName("disk_used_pct") val diskUsedPct: Double,
    @SerialName("disk_used_gb") val diskUsedGb: Double,
    @SerialName("disk_total_gb") val diskTotalGb: Double,
    val disks: List<PushDiskBody>,
    val os: String,
    val arch: String,
    @SerialName("collected_at") val collectedAt: String,
    val version: String = "",
    @SerialName("net_rx_mbps") val netRxMbps: Double = 0.0,
    @SerialName("net_tx_mbps") val netTxMbps: Double = 0.0,
    @SerialName("disk_read_iops") val diskReadIops: Double = 0.0,
    @SerialName("disk_write_iops") val diskWriteIops: Double = 0.0,
    @SerialName("disk_read_mb_s") val diskReadMbS: Double = 0.0,
    @SerialName("disk_write_mb_s") val diskWriteMbS: Double = 0.0,
    @SerialName("disk_util_pct") val diskUtilPct: Double = 0.0,
    @SerialName("disk_busy") val diskBusy: String = "",
)

private fun HostMetrics.toBody(serverId: String, agentVersion: String) = PushMetricsBody(
    serverId = serverId,
    cpuPct = cpuPct,
    load1 = load1,
    loadPct = loadPct,
    ncpu = ncpu,
    memPct = memPct,
    memUsedMb = memUsedMb,
    memTotalMb = memTotalMb,
    diskUsedPct = diskUsedPct,
    diskUsedGb = diskUsedGb,
    diskTotalGb = diskTotalGb,
    disks = disks.map {
        PushDiskBody(
            name = it.name,
            mount = it.mount,
            freeGb = it.freeGb,
            totalGb = it.totalGb,
            usedPct = it.usedPct,
            readIops = it.readIops,
            writeIops = it.writeIops,
            readMbS = it.readMbS,
            writeMbS = it.writeMbS,
            utilPct = it.utilPct,
        )
    },
    os = os,
    arch = arch,
    collectedAt = Instant.now().toString(),
    version = agentVersion,
    netRxMbps = netRxMbps,
    netTxMbps = netTxMbps,
    diskReadIops = diskReadIops,
    diskWriteIops = diskWriteIops,
    diskReadMbS = diskReadMbS,
    diskWriteMbS = diskWriteMbS,
    diskUtilPct = diskUtilPct,
    diskBusy = diskBusy,
)
