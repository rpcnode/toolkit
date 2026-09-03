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

    fun unit(
        env: String,
        nodeDir: String,
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
                "bin" to bin,
                "conf" to conf,
                "log_file" to logFile,
                "nofile" to nofile.coerceAtLeast(1024).toString(),
            ),
        )
    }

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
            appendLine("port_rpc_admin_local")
            appendLine("port_peer")
            appendLine("port_ws_admin_local")
            appendLine("port_ws_public")
            appendLine("port_grpc")
            appendLine()
            appendLine("[port_rpc_admin_local]")
            appendLine("port = ${ports.http}")
            appendLine("ip = 127.0.0.1")
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
            appendLine("ip = 127.0.0.1")
            appendLine("protocol = ws")
            appendLine()
            appendLine("[port_grpc]")
            appendLine("port = ${ports.grpc}")
            appendLine("ip = 127.0.0.1")
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
}
