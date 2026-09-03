package rpcnode.toolkit.chains.ethereum.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Renders ethereum systemd units from classpath templates under
 * `resources/chains/ethereum/` (`node.service.tmpl`, `lighthouse.service.tmpl`).
 */
object EthereumUnitBodies
{
    fun geth(
        env: String,
        bin: String,
        datadir: String,
        jwtPath: String,
        rpcPort: Int,
        p2pPort: Int,
        enginePort: Int,
        cluster: EthereumCluster,
        archive: Boolean,
        logFile: String,
    ): String
    {
        val syncmode = if (archive) "full" else "snap"
        val gcmode = if (archive) "archive" else "full"
        val historyFlag = if (cluster.historyPostMerge) " \\\n  --history.chain postmerge" else ""
        val networkFlag = if (cluster.gethFlag.isNotBlank()) " \\\n  ${cluster.gethFlag}" else ""
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("ethereum", "node.service.tmpl"),
            mapOf(
                "description" to "Ethereum Geth EL ($env) — RpcNode",
                "bin" to bin,
                "datadir" to datadir,
                "http_port" to rpcPort.toString(),
                "engine_port" to enginePort.toString(),
                "jwt_path" to jwtPath,
                "syncmode" to syncmode,
                "gcmode" to gcmode,
                "cache_mb" to EthereumClusters.cacheMb(env).toString(),
                "history_flag" to historyFlag,
                "p2p_port" to p2pPort.toString(),
                "network_flag" to networkFlag,
                "log_file" to logFile,
            ),
        )
    }

    fun lighthouse(
        env: String,
        bin: String,
        datadir: String,
        jwtPath: String,
        enginePort: Int,
        beaconPort: Int,
        consensusP2p: Int,
        cluster: EthereumCluster,
        gethUnit: String,
        logFile: String,
    ): String
    {
        val after = gethUnit.trim().let { if (it.endsWith(".service")) it else "$it.service" }
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("ethereum", "lighthouse.service.tmpl"),
            mapOf(
                "description" to "Ethereum Lighthouse CL ($env) — RpcNode",
                "geth_unit" to after,
                "bin" to bin,
                "lighthouse_network" to cluster.lighthouseNetwork,
                "datadir" to datadir,
                "engine_port" to enginePort.toString(),
                "jwt_path" to jwtPath,
                "checkpoint_url" to cluster.checkpointUrl,
                "beacon_port" to beaconPort.toString(),
                "consensus_p2p" to consensusP2p.toString(),
                "log_file" to logFile,
            ),
        )
    }

    fun lighthouseUnitName(env: String): String =
        "rpcnode-ethereum-lighthouse-${env.trim().lowercase()}.service"
}
