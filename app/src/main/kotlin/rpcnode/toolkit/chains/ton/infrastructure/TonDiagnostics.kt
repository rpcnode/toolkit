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
        """\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})[^\]]*\][^\n]*?last_masterchain_block_ago=([0-9]+(?:\.[0-9]+)?)([smhd])""",
    )
    private val tsFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** (time, lag seconds) of every STATUS line, oldest first. */
    fun statusReadings(text: String): List<Pair<LocalDateTime, Double>> =
        reStatus.findAll(text).mapNotNull { m ->
            val t = runCatching { LocalDateTime.parse(m.groupValues[1], tsFormat) }.getOrNull() ?: return@mapNotNull null
            val n = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            val mult = when (m.groupValues[3]) { "s" -> 1.0; "m" -> 60.0; "h" -> 3600.0; else -> 86400.0 }
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
