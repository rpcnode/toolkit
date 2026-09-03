package rpcnode.toolkit.chains.hyperliquid.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Renders hl-visor systemd unit + visor/gossip JSON.
 * Capacity literals must stay identical to `chains/hyperliquid/network.yml` clientConfig.bindings.
 */
object HyperliquidUnitBodies
{
    const val LIMIT_NOFILE = "1048576"
    const val RELATIVE_LOG = "logs/hl-visor.log"
    const val HL_SUBDIR = "hl"
    const val DATA_LINK = "hyperliquid_data"
    const val VISOR_JSON = "visor.json"
    const val GOSSIP_JSON = "override_gossip_config.json"
    const val BINARY = "hl-visor"

    fun unit(
        env: String,
        nodeDir: String,
        workdir: String,
        execStart: String,
        logFile: String,
        nofile: String = LIMIT_NOFILE,
    ): String
    {
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("hyperliquid", "node.service.tmpl"),
            mapOf(
                "description" to "Hyperliquid non-validator full RPC ($env) — RpcNode",
                "node_dir" to nodeDir,
                "workdir" to workdir,
                "exec_start" to execStart,
                "log_file" to logFile,
                "nofile" to nofile,
            ),
        )
    }

    fun execStart(visorBin: String): String =
        "$visorBin run-non-validator --replica-cmds-style actions --serve-eth-rpc --serve-info"

    fun visorJson(cluster: HyperliquidCluster): String =
        """{"chain": "${cluster.chainName}"}""" + "\n"

    fun gossipJson(cluster: HyperliquidCluster, peerIps: List<String>): String
    {
        val ips = peerIps.joinToString(",\n") { ip ->
            """    {"Ip": "$ip"}"""
        }
        return buildString {
            appendLine("{")
            appendLine("  \"root_node_ips\": [")
            appendLine(ips)
            appendLine("  ],")
            appendLine("  \"try_new_peers\": true,")
            appendLine("  \"chain\": \"${cluster.chainName}\"")
            appendLine("}")
        }
    }
}
