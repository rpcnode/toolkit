package rpcnode.toolkit.nodes.infrastructure.host.diagnostics

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import rpcnode.toolkit.chains.arb.infrastructure.ArbDiagnostics
import rpcnode.toolkit.chains.polkadot.infrastructure.PolkadotHistoryDiagnostics
import rpcnode.toolkit.chains.polkadot.infrastructure.PolkadotIndexerDiagnostics
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoDiagnostics
import rpcnode.toolkit.chains.ton.infrastructure.TonDiagnostics
import rpcnode.toolkit.chains.xrpl.infrastructure.XrplDiagnostics

/** One check from `chains/<id>/network.yml` → `diagnostics[]`, with the catalog port already resolved by the panel. */
data class DiagnosticRequest(
    val id: String,
    val port: Int = 0,
    val protocol: String = "",
    val params: Map<String, String> = emptyMap(),
)

/** level: ok | info | warn | error | unknown. */
data class DiagnosticResult(
    val id: String,
    val level: String,
    val title: String,
    val detail: String = "",
    val hint: String = "",
)

/** What a check may ask about the host (injectable for tests). */
class HostFacts(
    val publicIp: () -> String? = ::fetchPublicIp,
    val localIps: () -> List<String> = ::localIpv4,
    /** true when an ICMP echo with [payload] bytes and DF set reaches [target]. */
    val pingDf: (target: String, payload: Int) -> Boolean = ::pingDontFragment,
    /** MTU of the interface that routes to [target], null when unknown. */
    val routeMtu: (target: String) -> Int? = ::routeInterfaceMtu,
    /** net.ipv4.tcp_mtu_probing (0 = off). */
    val tcpMtuProbing: () -> Int? = { readSysctl("net/ipv4/tcp_mtu_probing")?.toIntOrNull() },
)

/**
 * Runs the node checks on the host agent. Generic checks live here, chain-specific ones in `chains/<id>`.
 * An unknown id is reported, not thrown — the panel may be newer than the agent.
 */
object HostNodeDiagnostics
{
    fun run(checks: List<DiagnosticRequest>, facts: HostFacts = HostFacts()): List<DiagnosticResult>
    {
        // public IP is asked once per run, not per check
        val cachedFacts = lazyFacts(facts)
        return checks.map { check ->
            runCatching {
                when (check.id.trim().lowercase())
                {
                    "nat" -> nat(check, cachedFacts)
                    "path_mtu" -> pathMtu(check, cachedFacts)
                    "ton_advertised_ip" -> TonDiagnostics.advertisedIp(check, cachedFacts)
                    "ton_network" -> TonDiagnostics.network(check)
                    "ton_http_api" -> TonDiagnostics.httpApi(check)
                    "ton_catchup" -> TonDiagnostics.catchUp(check)
                    "arb_l1" -> ArbDiagnostics.parentChain(check)
                    "xrpl_sync" -> XrplDiagnostics.sync(check)
                    "polkadot_indexer" -> PolkadotIndexerDiagnostics.check(check)
                    "polkadot_history" -> PolkadotHistoryDiagnostics.check(check)
                    "cardano_ogmios" -> CardanoDiagnostics.ogmios(check)
                    "cardano_dbsync" -> CardanoDiagnostics.dbSync(check)
                    else -> DiagnosticResult(check.id, "unknown", "Unknown check «${check.id}»", "Update the host agent")
                }
            }.getOrElse { e ->
                DiagnosticResult(check.id, "unknown", "Check «${check.id}» failed", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Inbound P2P needs the public address on this host, or a port forward on the router. A forward cannot be seen
     * from the inside, so behind NAT this is a warning with the exact forward to make, not a verdict.
     */
    fun nat(check: DiagnosticRequest, facts: HostFacts): DiagnosticResult
    {
        val pub = facts.publicIp()
            ?: return DiagnosticResult(check.id, "unknown", "Public IP unknown", "api.ipify.org / ifconfig.me did not answer")
        val locals = facts.localIps()
        if (pub in locals)
        {
            return DiagnosticResult(check.id, "ok", "Public IP is on this host", pub)
        }
        val proto = check.protocol.ifBlank { "tcp" }.uppercase()
        val local = lanAddress(locals) ?: "this host"
        val port = check.port.takeIf { it > 0 }
        return DiagnosticResult(
            id = check.id,
            level = "warn",
            title = "Host is behind NAT",
            detail = "Public $pub, this host ${locals.joinToString(", ").ifBlank { "—" }}. " +
                (if (port != null) "Peers reach P2P $proto $port only if the router forwards it here; " else "") +
                "without it the node gets few peers and syncs slowly.",
            hint = if (port != null) "Router: forward $proto $port → $local:$port" else "",
        )
    }

    /**
     * PMTU black hole: the path to the internet is smaller than the interface MTU (PPPoE: 1492 < 1500) and the
     * router's "fragmentation needed" never arrives. Downloads still work, but full-size packets *from* the node are
     * dropped — peers time out (XRPL: Ping Timeout, empty complete_ledgers; slow TON/P2P catch-up).
     */
    fun pathMtu(check: DiagnosticRequest, facts: HostFacts): DiagnosticResult
    {
        val target = check.params["target"]?.trim()?.ifEmpty { null } ?: "1.1.1.1"
        val ifaceMtu = facts.routeMtu(target) ?: 1500
        val maxPayload = ifaceMtu - 28
        if (facts.pingDf(target, maxPayload))
        {
            return DiagnosticResult(check.id, "ok", "Path MTU matches the interface ($ifaceMtu)")
        }
        if (!facts.pingDf(target, 548))
        {
            return DiagnosticResult(check.id, "unknown", "Path MTU unknown", "ICMP to $target does not answer")
        }
        // largest payload that still passes, between 548 and the interface limit
        var lo = 548
        var hi = maxPayload
        while (hi - lo > 1)
        {
            val mid = (lo + hi) / 2
            if (facts.pingDf(target, mid)) lo = mid else hi = mid
        }
        val pathMtu = lo + 28
        val probing = facts.tcpMtuProbing() ?: 0
        val detail = "The path to the internet carries $pathMtu-byte packets, the interface sends $ifaceMtu. " +
            "Larger packets from this host are dropped silently (no «fragmentation needed»), so peers time out."
        return if (probing > 0)
        {
            DiagnosticResult(check.id, "info", "Path MTU $pathMtu < interface $ifaceMtu (TCP MTU probing is on)", detail)
        }
        else
        {
            DiagnosticResult(
                check.id,
                "warn",
                "Path MTU $pathMtu < interface MTU $ifaceMtu",
                detail,
                "echo 'net.ipv4.tcp_mtu_probing = 1' | sudo tee /etc/sysctl.d/99-rpcnode-mtu.conf && sudo sysctl --system\n" +
                    "# or: MSS clamping on the router / interface MTU $pathMtu — then restart the node",
            )
        }
    }

    private fun lazyFacts(facts: HostFacts): HostFacts
    {
        val pub by lazy { facts.publicIp() }
        val locals by lazy { facts.localIps() }
        return HostFacts(
            publicIp = { pub },
            localIps = { locals },
            pingDf = facts.pingDf,
            routeMtu = facts.routeMtu,
            tcpMtuProbing = facts.tcpMtuProbing,
        )
    }

    /**
     * The address a router forwards to: 192.168/16 and 10/8 first. 172.16/12 comes last — on a node host it is
     * usually the Docker / libvirt bridge (172.17.0.1), not the LAN.
     */
    internal fun lanAddress(locals: List<String>): String? =
        locals.firstOrNull { it.startsWith("192.168.") }
            ?: locals.firstOrNull { it.startsWith("10.") }
            ?: locals.firstOrNull { isPrivate(it) }
            ?: locals.firstOrNull()

    internal fun isPrivate(ip: String): Boolean =
        ip.startsWith("10.") || ip.startsWith("192.168.") ||
            Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(ip) || ip.startsWith("100.64.")
}

private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
    .connectTimeout(Duration.ofSeconds(4)).build()

fun fetchPublicIp(): String?
{
    for (url in listOf("https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com"))
    {
        val ip = runCatching {
            val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(5)).GET().build()
            http.send(req, HttpResponse.BodyHandlers.ofString()).takeIf { it.statusCode() == 200 }?.body()?.trim()
        }.getOrNull()
        if (ip != null && Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(ip))
        {
            return ip
        }
    }
    return null
}

fun localIpv4(): List<String> =
    runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
    }.getOrDefault(emptyList())

fun pingDontFragment(target: String, payload: Int): Boolean =
    runCatching {
        val p = ProcessBuilder("ping", "-c", "1", "-W", "2", "-M", "do", "-s", payload.toString(), target)
            .redirectErrorStream(true)
            .start()
        p.inputStream.readAllBytes()
        p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

fun routeInterfaceMtu(target: String): Int? =
    runCatching {
        val p = ProcessBuilder("ip", "route", "get", target).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        val dev = Regex("""\bdev\s+(\S+)""").find(out)?.groupValues?.get(1) ?: return@runCatching null
        java.nio.file.Files.readString(java.nio.file.Path.of("/sys/class/net/$dev/mtu")).trim().toIntOrNull()
    }.getOrNull()

private fun readSysctl(path: String): String? =
    runCatching { java.nio.file.Files.readString(java.nio.file.Path.of("/proc/sys/$path")).trim() }.getOrNull()
