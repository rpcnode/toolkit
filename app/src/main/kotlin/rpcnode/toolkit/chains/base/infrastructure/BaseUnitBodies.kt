package rpcnode.toolkit.chains.base.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Renders Base systemd units / scripts from classpath templates under
 * `resources/chains/base/`.
 */
object BaseUnitBodies
{
    fun reth(
        env: String,
        bin: String,
        datadir: String,
        jwtPath: String,
        rpcPort: Int,
        wsPort: Int,
        enginePort: Int,
        p2pPort: Int,
        discoveryV5: Int,
        cluster: BaseCluster,
        logFile: String,
    ): String
    {
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("base", "node.service.tmpl"),
            mapOf(
                "description" to "Base base-reth-node ($env, full history) — RpcNode",
                "bin" to bin,
                "datadir" to datadir,
                "http_port" to rpcPort.toString(),
                "ws_port" to wsPort.toString(),
                "engine_port" to enginePort.toString(),
                "jwt_path" to jwtPath,
                "reth_chain" to cluster.rethChain,
                "sequencer_http" to cluster.sequencerHttp,
                "p2p_port" to p2pPort.toString(),
                "discovery_v5" to discoveryV5.toString(),
                "log_file" to logFile,
            ),
        )
    }

    fun consensus(
        env: String,
        wrapper: String,
        etc: String,
        rethUnit: String,
        logFile: String,
    ): String
    {
        val after = rethUnit.trim().let { if (it.endsWith(".service")) it else "$it.service" }
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("base", "consensus.service.tmpl"),
            mapOf(
                "description" to "Base base-consensus ($env) — RpcNode",
                "reth_unit" to after,
                "etc" to etc,
                "wrapper" to wrapper,
                "log_file" to logFile,
            ),
        )
    }

    fun consensusUnitName(env: String): String =
        "rpcnode-base-consensus-${env.trim().lowercase()}.service"

    fun consensusEnv(
        cluster: BaseCluster,
        l1Rpc: String,
        l1Beacon: String,
        jwtPath: String,
        jwtRaw: String,
        enginePort: Int,
        consensusP2p: Int,
    ): String
    {
        val quoted = "\"" + jwtRaw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return buildString {
            appendLine("# managed by rpcnode base provision")
            appendLine("BASE_NODE_NETWORK=${cluster.networkFlag}")
            appendLine("BASE_NODE_L1_ETH_RPC=$l1Rpc")
            appendLine("BASE_NODE_L1_BEACON=$l1Beacon")
            appendLine("BASE_NODE_L1_TRUST_RPC=false")
            appendLine("BASE_NODE_L2_ENGINE_RPC=http://127.0.0.1:$enginePort")
            appendLine("BASE_NODE_L2_ENGINE_AUTH=$jwtPath")
            appendLine("BASE_NODE_L2_ENGINE_AUTH_RAW=$quoted")
            appendLine("BASE_NODE_P2P_LISTEN_IP=0.0.0.0")
            appendLine("BASE_NODE_P2P_ADVERTISE_TCP_PORT=$consensusP2p")
            appendLine("BASE_NODE_P2P_ADVERTISE_UDP_PORT=$consensusP2p")
            appendLine("BASE_NODE_P2P_BOOTNODES=${BaseClusters.BOOTNODES}")
            appendLine("BASE_NODE_LOG_VERBOSITY=3")
            appendLine("BASE_NODE_LOG_FORMAT=json")
        }
    }

    fun consensusWrapper(consensusBin: String): String
    {
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("base", "consensus_wrapper.sh.tmpl"),
            mapOf("consensus_bin" to consensusBin),
        )
    }

    fun writeExecutable(path: Path, body: String)
    {
        Files.createDirectories(path.parent)
        Files.writeString(path, body)
        path.toFile().setExecutable(true)
    }
}
