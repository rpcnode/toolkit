package rpcnode.toolkit.chains.avalanche.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Renders the avalanchego systemd unit, its command line and the C-Chain `config.json`.
 * Capacity literals must stay identical to `chains/avalanche/network.yml` clientConfig.bindings.
 */
object AvalancheUnitBodies
{
    const val NODE_NOFILE = 1_048_576
    const val HTTP_HOST = "0.0.0.0"
    const val HTTP_ALLOWED_HOSTS = "*"
    const val API_ADMIN_ENABLED = false
    const val INDEX_ENABLED = false
    const val RPC_GAS_CAP = 50_000_000
    const val RPC_TX_FEE_CAP = 100
    val ETH_APIS = listOf(
        "eth", "eth-filter", "net", "web3", "internal-eth", "internal-blockchain",
        "internal-transaction", "internal-tx-pool", "debug",
    )

    const val RELATIVE_LOG = "logs/avalanche.log"

    /** Everything avalanchego derives from `--data-dir` (staking keys, configs, plugins) lives here under the node dir. */
    const val DATA_DIR = "data"

    fun unit(
        env: String,
        nodeDir: String,
        execStart: String,
        logFile: String,
        nofile: Int = NODE_NOFILE,
    ): String
    {
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("avalanche", "node.service.tmpl"),
            mapOf(
                "description" to "Avalanche avalanchego ($env) — RpcNode",
                "node_dir" to nodeDir,
                "exec_start" to execStart,
                "log_file" to logFile,
                "nofile" to nofile.coerceAtLeast(1024).toString(),
            ),
        )
    }

    /** avalanchego command line after the binary. */
    fun args(
        env: String,
        dataDir: String,
        dbDir: String,
        logDir: String,
        httpPort: Int,
        stakingPort: Int,
    ): List<String>
    {
        return listOf(
            "--network-id=${AvalancheClusters.networkFlag(env)}",
            "--data-dir=$dataDir",
            "--db-dir=$dbDir",
            "--log-dir=$logDir",
            "--http-host=$HTTP_HOST",
            "--http-port=$httpPort",
            "--http-allowed-hosts=$HTTP_ALLOWED_HOSTS",
            "--staking-port=$stakingPort",
            "--api-admin-enabled=$API_ADMIN_ENABLED",
            "--index-enabled=$INDEX_ENABLED",
        )
    }

    /**
     * C-Chain `configs/chains/C/config.json`. full = pruned + state sync (hours to sync); archive = pruning off
     * (state sync is ignored there, the chain replays from genesis).
     */
    fun cChainConfig(mode: String): String
    {
        val archive = AvalancheNodeMode.normalize(mode) == AvalancheNodeMode.ARCHIVE
        val apis = ETH_APIS.joinToString(",") { "\"$it\"" }
        return buildString {
            appendLine("{")
            appendLine("  \"pruning-enabled\": ${!archive},")
            appendLine("  \"state-sync-enabled\": ${!archive},")
            appendLine("  \"eth-apis\": [$apis],")
            appendLine("  \"rpc-gas-cap\": $RPC_GAS_CAP,")
            appendLine("  \"rpc-tx-fee-cap\": $RPC_TX_FEE_CAP")
            appendLine("}")
        }
    }
}
