package rpcnode.toolkit.chains.xrpl.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.chains.xrpl.infrastructure.XrplClusters
import rpcnode.toolkit.chains.xrpl.infrastructure.XrplHistory
import rpcnode.toolkit.chains.xrpl.infrastructure.XrplHistoryPolicy
import rpcnode.toolkit.chains.xrpl.infrastructure.XrplUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Local XRPL height via `server_info` validated_ledger.seq.
 * Sync % mirrors Go VerificationPct (history window vs tip).
 */
class XrplNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
) : HostNodeHeightProbe
{
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long?
    {
        return reading(nodeDir, httpPort, configFile, env)?.height?.takeIf { it >= 0 }
    }

    override suspend fun reading(
        nodeDir: String,
        httpPort: Int,
        configFile: String,
        env: String,
    ): HostNodeHeightReading?
    {
        if (httpPort <= 0)
        {
            return null
        }
        val info = XrplRpc.serverInfo(http, "http://127.0.0.1:$httpPort") ?: return null
        if (!info.ok)
        {
            return null
        }
        val envId = XrplClusters.normalizeEnv(env)
        val policy = loadPolicy(nodeDir)
        val (lo, hi) = XrplHistory.parseCompleteLedgers(info.complete)
        val live = XrplHistory.tipLive(info.state)
        val histOk = XrplHistory.historyOk(envId, lo, hi, info.seq, policy)
        val pct = XrplHistory.verificationPct(
            live = live,
            historyOk = histOk,
            lo = lo,
            hi = hi,
            seq = info.seq,
            genesis = XrplClusters.genesisLedger(envId),
            target = policy.ledgers,
        )
        val syncing = !(live && histOk)
        if (info.seq <= 0)
        {
            return HostNodeHeightReading(height = 0, syncPct = pct.takeIf { it > 0 }, syncing = true)
        }
        return HostNodeHeightReading(
            height = info.seq,
            syncPct = pct,
            syncing = syncing,
        )
    }

    private fun loadPolicy(nodeDir: String): XrplHistoryPolicy
    {
        val root = Path.of(nodeDir.trim())
        val historyFile = root.resolve(XrplUnitBodies.HISTORY_JSON)
        if (Files.isRegularFile(historyFile))
        {
            val raw = runCatching { Files.readString(historyFile) }.getOrNull().orEmpty()
            val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            if (obj != null)
            {
                val mode = obj["mode"]?.jsonPrimitive?.contentOrNull
                val ledgers = obj["ledgers"]?.jsonPrimitive?.intOrNull
                if (mode == XrplHistory.FULL)
                {
                    return XrplHistoryPolicy(XrplHistory.FULL, 0)
                }
                if (ledgers != null && ledgers >= 256)
                {
                    return XrplHistoryPolicy(mode ?: "custom", ledgers)
                }
                if (!mode.isNullOrBlank())
                {
                    return XrplHistory.parse(mode)
                }
            }
        }
        return XrplHistory.DEFAULT
    }
}
