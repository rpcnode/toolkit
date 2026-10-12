package rpcnode.toolkit.chains.ton.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.Locale
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.DiagnosticRequest
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.DiagnosticResult
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.HostFacts

/** TON checks declared in `chains/ton/network.yml` → `diagnostics`. MyTonCtrl keeps everything under /var/ton-work. */
object TonDiagnostics
{
    private val work = Path.of("/var/ton-work")

    /** The validator tells the network `addrs[]` from its config: behind NAT it must be the public IP, not the LAN one. */
    fun advertisedIp(
        check: DiagnosticRequest,
        facts: HostFacts,
        configText: () -> String? = { readText(work.resolve("db").resolve("config.json")) },
    ): DiagnosticResult
    {
        val text = configText()
            ?: return DiagnosticResult(check.id, "unknown", "Validator config not readable", "/var/ton-work/db/config.json")
        val addrs = parseAddrs(text)
        if (addrs.isEmpty())
        {
            return DiagnosticResult(check.id, "warn", "Validator advertises no address", "config.json has no addrs[]")
        }
        val pub = facts.publicIp()
        val shown = addrs.joinToString(", ") { "${it.first}:${it.second}" }
        val wantPort = check.port.takeIf { it > 0 }
        val ipOk = pub == null || addrs.any { it.first == pub }
        val portOk = wantPort == null || addrs.any { it.second == wantPort }
        return when
        {
            ipOk && portOk -> DiagnosticResult(check.id, "ok", "Validator advertises $shown")
            !ipOk -> DiagnosticResult(
                check.id,
                "error",
                "Validator advertises the wrong address",
                "Advertised $shown, public IP is $pub — peers cannot connect back.",
                "Set the public IP in /var/ton-work/db/config.json addrs[] (or re-run MyTonCtrl with it) and restart validator",
            )
            else -> DiagnosticResult(
                check.id,
                "warn",
                "Validator advertises another port",
                "Advertised $shown, catalog P2P port is $wantPort.",
            )
        }
    }

    /**
     * Which network the host's validator really follows: the zero state in `/usr/bin/ton/global.config.json` against
     * the one of the node's env. A host that already ran another TON network keeps its old config, and the node
     * labelled «testnet» then syncs mainnet (heights ~98 M against a ~90 M testnet tip, the wrong dump size).
     */
    fun network(
        check: DiagnosticRequest,
        configText: () -> String? = { readText(Path.of("/usr/bin/ton/global.config.json")) },
    ): DiagnosticResult
    {
        val env = check.params["env"]?.trim()?.lowercase().orEmpty()
        if (env != "mainnet" && env != "testnet")
        {
            return DiagnosticResult(check.id, "unknown", "Unknown TON env «$env»")
        }
        val text = configText()
            ?: return DiagnosticResult(check.id, "unknown", "global.config.json not readable", "/usr/bin/ton/global.config.json")
        val have = zeroState(text)
            ?: return DiagnosticResult(check.id, "unknown", "No zero state in global.config.json")
        val want = TonClusters.zeroStateHash(env)
        if (have == want)
        {
            return DiagnosticResult(check.id, "ok", "The validator follows $env")
        }
        val other = if (have == TonClusters.zeroStateHash("mainnet")) "mainnet" else if (have == TonClusters.zeroStateHash("testnet")) "testnet" else "an unknown network"
        return DiagnosticResult(
            id = check.id,
            level = "error",
            title = "This «$env» node syncs $other",
            detail = "/usr/bin/ton/global.config.json is the config of $other (zero state ${have.take(8)}…, $env expects ${want.take(8)}…), " +
                "so the validator and the dump it downloaded belong to $other; the panel compares its height with the $env tip.",
            hint = "One TON network per host: remove this node with its data, then add it again — the install now replaces the config " +
                "(old one kept as global.config.json.bak-*).",
        )
    }

    /** Hex (upper) zero-state root hash of a TON global config; the file stores it base64. */
    fun zeroState(configJson: String): String? =
        runCatching {
            val b64 = Json.parseToJsonElement(configJson).jsonObject["validator"]!!.jsonObject["zero_state"]!!
                .jsonObject["root_hash"]!!.jsonPrimitive.content
            java.util.Base64.getDecoder().decode(b64).joinToString("") { "%02X".format(it) }
        }.getOrNull()

    /**
     * The panel reads the height from the TON HTTP API on the catalog port. A unit left by an older install can keep
     * another `--port` (8081 on a testnet host…): the API runs fine, the panel sees nothing (height 0, no bar).
     */
    fun httpApi(
        check: DiagnosticRequest,
        status: (port: Int) -> Int? = ::httpStatus,
        listening: () -> String = ::listeningSockets,
    ): DiagnosticResult
    {
        val port = check.port.takeIf { it > 0 }
            ?: return DiagnosticResult(check.id, "unknown", "No HTTP API port in the catalog")
        when (status(port))
        {
            200 -> return DiagnosticResult(check.id, "ok", "TON HTTP API answers on $port")
            503 -> return DiagnosticResult(check.id, "info", "TON HTTP API on $port answers 503", "Normal while the liteserver is still catching up.")
            else -> Unit
        }
        val other = apiPorts(listening()).filter { it != port }
        return if (other.isNotEmpty())
        {
            DiagnosticResult(
                check.id,
                "error",
                "TON HTTP API listens on ${other.joinToString(", ")}, not on $port",
                "The panel reads the height from port $port, so it shows height 0 and no progress while the node may be fine.",
                "sudo sed -i -E 's/--port [0-9]+/--port $port/' /etc/systemd/system/ton_http_api.service && " +
                    "sudo systemctl daemon-reload && sudo systemctl restart ton_http_api",
            )
        }
        else
        {
            DiagnosticResult(
                check.id,
                "warn",
                "TON HTTP API is not listening on $port",
                "ton_http_api is down or still starting.",
                "systemctl status ton_http_api --no-pager",
            )
        }
    }

    /** Ports `ton-http-api` listens on, from `ss -ltnp` text. */
    fun apiPorts(ssOutput: String): List<Int> =
        ssOutput.lineSequence()
            .filter { it.contains("ton-http-api") }
            .mapNotNull { Regex("""[\d.\]\[:*a-f]*:(\d+)\s""").find(it.substringAfter("LISTEN"))?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .toList()

    private fun httpStatus(port: Int): Int? =
        runCatching {
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/getMasterchainInfo"))
                .timeout(java.time.Duration.ofSeconds(5)).GET().build()
            // HTTP/1.1 only: the default client sends an h2c Upgrade and uvicorn (ton-http-api) answers it with 400
            java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(3)).build()
                .send(req, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()
        }.getOrNull()

    private fun listeningSockets(): String =
        runCatching {
            val p = ProcessBuilder("ss", "-ltnp").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            out
        }.getOrDefault("")

    /**
     * Catch-up from the dump: lag of the validator's STATUS lines over the last hour. Warns when the remaining lag
     * would take longer than `warnHours` (default 12) at the current speed, or when it does not shrink at all.
     */
    fun catchUp(
        check: DiagnosticRequest,
        logText: () -> String = ::recentValidatorLogs,
    ): DiagnosticResult
    {
        val readings = statusReadings(logText())
        val last = readings.lastOrNull()
            ?: return DiagnosticResult(check.id, "unknown", "No validator status yet", "no STATUS line in /var/ton-work/log*.log")
        val lagH = last.second / 3600.0
        if (last.second <= 120)
        {
            return DiagnosticResult(check.id, "ok", "Validator is in sync", "last masterchain block ${last.second.roundToInt()} s ago")
        }
        val warnHours = check.params["warnHours"]?.toDoubleOrNull() ?: 12.0
        // compare with a reading ~1 h older (at least 15 min) so a single noisy line does not decide
        val older = readings.lastOrNull { java.time.Duration.between(it.first, last.first).toMinutes() >= 60 }
            ?: readings.firstOrNull { java.time.Duration.between(it.first, last.first).toMinutes() >= 15 }
        if (older == null)
        {
            return DiagnosticResult(check.id, "info", "Validator is ${fmtH(lagH)} behind", "speed is measured after ~15 min of status lines")
        }
        val hours = java.time.Duration.between(older.first, last.first).toSeconds() / 3600.0
        val closedPerHour = (older.second - last.second) / 3600.0 / hours
        if (closedPerHour <= 0.0)
        {
            return DiagnosticResult(
                check.id,
                "warn",
                "Validator is ${fmtH(lagH)} behind and not catching up",
                "The lag did not shrink over the last ${fmtH(hours)}.",
                "Usually too few peers: check P2P reachability (NAT / port forward) and the advertised address",
            )
        }
        val eta = lagH / closedPerHour
        val detail = "Closes ${"%.2f".format(Locale.ROOT, closedPerHour)} h of lag per hour — about ${fmtH(eta)} to go."
        return if (eta > warnHours)
        {
            DiagnosticResult(
                check.id,
                "warn",
                "Slow catch-up: ${fmtH(lagH)} behind",
                detail,
                "Faster with more peers: open/forward the P2P port, check the advertised address",
            )
        }
        else
        {
            DiagnosticResult(check.id, "info", "Catching up: ${fmtH(lagH)} behind", detail)
        }
    }

    private val reStatus = Regex(
        """\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})[^\]]*\][^\n]*?last_masterchain_block_ago=([0-9]+(?:\.[0-9]+)?)(ms|[smhd])""",
    )
    private val tsFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** (time, lag seconds) of every STATUS line, oldest first. */
    fun statusReadings(text: String): List<Pair<LocalDateTime, Double>> =
        reStatus.findAll(text).mapNotNull { m ->
            val t = runCatching { LocalDateTime.parse(m.groupValues[1], tsFormat) }.getOrNull() ?: return@mapNotNull null
            val n = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            val mult = when (m.groupValues[3]) { "ms" -> 0.001; "s" -> 1.0; "m" -> 60.0; "h" -> 3600.0; else -> 86400.0 }
            t to n * mult
        }.sortedBy { it.first }.toList()

    /** (ip, port) from validator config.json `addrs[]`; the ip is a signed int there. */
    fun parseAddrs(configJson: String): List<Pair<String, Int>> =
        runCatching {
            Json.parseToJsonElement(configJson).jsonObject["addrs"]?.jsonArray.orEmpty().mapNotNull { el ->
                val o = el.jsonObject
                val raw = o["ip"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val port = o["port"]?.jsonPrimitive?.int ?: 0
                val ip = listOf(24, 16, 8, 0).joinToString(".") { ((raw ushr it) and 0xff).toString() }
                ip to port
            }
        }.getOrDefault(emptyList())

    private fun fmtH(h: Double): String =
        if (h < 1.0) "${(h * 60).roundToInt()} min" else "${"%.1f".format(Locale.ROOT, h)} h"

    private fun readText(p: Path): String? = runCatching { Files.readString(p) }.getOrNull()

    /** Tails of the validator thread logs touched in the last 3 hours (it logs per thread). */
    private fun recentValidatorLogs(): String
    {
        if (!Files.isDirectory(work))
        {
            return ""
        }
        val cutoff = System.currentTimeMillis() - 3 * 3600_000L
        val files = Files.list(work).use { s ->
            s.filter { f ->
                Files.isRegularFile(f) && f.fileName.toString().let { it.startsWith("log") && it.endsWith(".log") } &&
                    Files.getLastModifiedTime(f).toMillis() >= cutoff
            }.toList()
        }
        return files.joinToString("\n") { tail(it, 256 * 1024) }
    }

    private fun tail(file: Path, bytes: Int): String =
        runCatching {
            java.io.RandomAccessFile(file.toFile(), "r").use { raf ->
                val from = (raf.length() - bytes).coerceAtLeast(0)
                raf.seek(from)
                val buf = ByteArray((raf.length() - from).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        }.getOrDefault("")
}
