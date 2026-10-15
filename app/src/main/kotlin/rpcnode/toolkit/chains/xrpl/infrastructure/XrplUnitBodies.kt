package rpcnode.toolkit.chains.xrpl.infrastructure

import java.nio.file.Path
import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Renders xrpld systemd unit + stock xrpld.cfg.
 * Capacity literals must stay identical to `chains/xrpl/network.yml` clientConfig.bindings.
 */
object XrplUnitBodies
{
    const val NODE_NOFILE = 1_048_576
    const val PEERS_MAX = 100
    const val NODE_SIZE = "medium"

    const val CFG_NAME = "xrpld.cfg"
    const val VALIDATORS_NAME = "validators.txt"
    const val HISTORY_JSON = "history.json"
    const val RELATIVE_LOG = "logs/xrpld.log"
    const val BIN_NAME = "xrpld"
    const val RUN_SCRIPT = "bin/rpcnode-xrpl-run.sh"

    /** The C++ standard xrpld requires (C++23) needs the newest compiler the CI tests. */
    const val CLANG_MAJOR = 22

    fun unit(
        env: String,
        nodeDir: String,
        execStart: String,
        bin: String,
        conf: String,
        logFile: String,
        nofile: Int = NODE_NOFILE,
    ): String
    {
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("xrpl", "node.service.tmpl"),
            mapOf(
                "description" to "XRP Ledger stock xrpld ($env) — RpcNode",
                "node_dir" to nodeDir,
                "exec_start" to execStart,
                "bin" to bin,
                "conf" to conf,
                "log_file" to logFile,
                "nofile" to nofile.coerceAtLeast(1024).toString(),
            ),
        )
    }

    /** Runs the [prebuilt] xrpld when it works here, else builds it from [srcTar] (once per release); the unit's main process. */
    fun runScript(nodeDir: String, srcTar: String, bin: String, conf: String, logFile: String, prebuilt: String = ""): String
    {
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("xrpl", "run.sh.tmpl"),
            mapOf(
                "root" to q(nodeDir),
                "src_tar" to q(srcTar),
                "prebuilt" to q(prebuilt),
                "bin" to q(bin),
                "conf" to q(conf),
                "log_file" to q(logFile),
                "clang_major" to CLANG_MAJOR.toString(),
                "clang_functions" to HostSystemdUnitTemplate.load("default", "clang-functions.sh").trimEnd(),
            ),
        )
    }

    private fun q(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    fun cfg(
        cluster: XrplCluster,
        etc: Path,
        data: Path,
        ports: XrplPorts,
        policy: XrplHistoryPolicy,
        hasLedger: Boolean,
        nodeSize: String = NODE_SIZE,
        peersMax: Int = PEERS_MAX,
    ): String
    {
        val env = cluster.env
        val dbPath = data.resolve("db").toAbsolutePath().normalize()
        val nudbPath = dbPath.resolve("nudb")
        val debugLog = data.resolve("debug.log").toAbsolutePath().normalize()
        val validators = etc.resolve(VALIDATORS_NAME).toAbsolutePath().normalize()
        val size = nodeSize.trim().ifEmpty { NODE_SIZE }

        return buildString {
            appendLine("# managed by RpcNode — stock xrpld (non-validator)")
            appendLine("# https://xrpl.org/docs/infrastructure/configuration/server-modes/run-xrpld-as-a-stock-server")
            appendLine()
            appendLine("[server]")
            appendLine("port_rpc")
            appendLine("port_peer")
            appendLine("port_ws_admin_local")
            appendLine("port_ws_public")
            appendLine("port_grpc")
            appendLine()
            // Listens on every interface so ingest / the admin / another host can reach the node. `admin` is a list of SOURCE
            // addresses: only a client connecting from this host (the agent, `server_info`/`feature` checks) gets admin
            // rights, everybody else gets the public command set (the stock-server defaults of the XRPL docs).
            appendLine("[port_rpc]")
            appendLine("port = ${ports.http}")
            appendLine("ip = 0.0.0.0")
            appendLine("admin = 127.0.0.1")
            appendLine("protocol = http")
            appendLine()
            appendLine("[port_peer]")
            appendLine("port = ${ports.p2p}")
            appendLine("ip = 0.0.0.0")
            appendLine("protocol = peer")
            appendLine()
            appendLine("[port_ws_admin_local]")
            appendLine("port = ${ports.wsAdmin}")
            appendLine("ip = 127.0.0.1")
            appendLine("admin = 127.0.0.1")
            appendLine("protocol = ws")
            appendLine("send_queue_limit = 500")
            appendLine()
            appendLine("[port_ws_public]")
            appendLine("port = ${ports.ws}")
            appendLine("ip = 0.0.0.0")
            appendLine("protocol = ws")
            appendLine()
            appendLine("[port_grpc]")
            appendLine("port = ${ports.grpc}")
            appendLine("ip = 0.0.0.0")
            appendLine("secure_gateway = 127.0.0.1")
            appendLine()
            // Empty NuDB: medium even on large hosts — huge cache init stalls the job queue.
            appendLine("[node_size]")
            appendLine(size)
            appendLine()
            appendLine("[node_db]")
            appendLine("type=NuDB")
            appendLine("path=$nudbPath")
            if (policy.mode != XrplHistory.FULL && policy.ledgers > 0 && hasLedger)
            {
                appendLine("online_delete=${policy.ledgers}")
            }
            appendLine("advisory_delete=0")
            appendLine()
            if (policy.mode == XrplHistory.FULL || policy.ledgers <= 0)
            {
                appendLine("[ledger_history]")
                appendLine("full")
                appendLine()
            }
            else
            {
                appendLine("[ledger_history]")
                appendLine(policy.ledgers.toString())
                appendLine()
            }
            appendLine("[peers_max]")
            appendLine(peersMax.toString())
            appendLine()
            appendLine("[fetch_depth]")
            appendLine("full")
            appendLine()
            appendLine("[database_path]")
            appendLine(dbPath.toString())
            appendLine()
            appendLine("[debug_logfile]")
            appendLine(debugLog.toString())
            appendLine()
            appendLine("[sntp_servers]")
            appendLine("time.windows.com")
            appendLine("time.apple.com")
            appendLine("time.nist.gov")
            appendLine("pool.ntp.org")
            appendLine()
            appendLine("[validators_file]")
            appendLine(validators.toString())
            appendLine()
            appendLine("[rpc_startup]")
            appendLine("{ \"command\": \"log_level\", \"severity\": \"warning\" }")
            appendLine()
            appendLine("[ssl_verify]")
            appendLine("0")
            if (cluster.networkId.isNotEmpty())
            {
                appendLine()
                appendLine("[network_id]")
                appendLine(cluster.networkId)
            }
            if (env != "testnet" && cluster.ips.isNotEmpty())
            {
                appendLine()
                appendLine("[ips]")
                for (hub in cluster.ips)
                {
                    appendLine(hub)
                }
            }
            if (cluster.ipsFixed.isNotEmpty())
            {
                appendLine()
                appendLine("[ips_fixed]")
                for (ip in cluster.ipsFixed)
                {
                    appendLine(ip)
                }
            }
        }
    }

    /** Single-line cfg sections the operator may override through Node config → Flag overrides, with their value check. */
    private val CFG_OVERRIDES: Map<String, (String) -> Boolean> = mapOf(
        "node_size" to { v -> v in setOf("tiny", "small", "medium", "large", "huge") },
        "peers_max" to { v -> v.toIntOrNull()?.let { it in 1..1000 } == true },
        "crawl" to { v -> v == "0" || v == "1" },
    )

    /**
     * Applies `key=value` overrides to the generated cfg: the single value line under `[key]` is replaced, a missing
     * section is appended. rippled ignores a second `[node_size]`-style section (it needs exactly one line), so the
     * existing line must be rewritten, never duplicated. Unknown keys and bad values are dropped.
     */
    fun applyCfgOverrides(cfg: String, text: String?): String
    {
        val overrides = rpcnode.toolkit.nodes.infrastructure.host.UnitFlagOverrides.parse(text).overrides
        var out = cfg
        for ((key, value) in overrides)
        {
            val valid = CFG_OVERRIDES[key] ?: continue
            if (!valid(value))
            {
                continue
            }
            val section = Regex("(?m)^\\[" + Regex.escape(key) + "\\][ \\t]*\\n[^\\n\\[]*")
            val hit = section.find(out)
            out = if (hit != null)
            {
                out.replaceRange(hit.range, "[$key]\n$value")
            }
            else
            {
                out.trimEnd('\n') + "\n\n[$key]\n$value\n"
            }
        }
        return out
    }
}
