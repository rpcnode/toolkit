package rpcnode.toolkit.chains.ethereum.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.ethereum.infrastructure.EthereumClusters
import rpcnode.toolkit.chains.ethereum.infrastructure.ethereumNodeMode
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * Geth EL + lighthouse CL. Launch args encode mode + disk roles for
 * [rpcnode.toolkit.chains.ethereum.infrastructure.proc.EthereumNodeProcessStarter].
 * Entry is the geth binary path relative to node_dir after extract.
 */
class EthereumNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.ETHEREUM

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = ctx.env.trim().lowercase().ifEmpty { "mainnet" }
        val cluster = EthereumClusters.lookup(env)
        val archive = ethereumNodeMode(ctx.installOptionsJson) == "archive"
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        // Binary lives in the execution role leaf as `geth`; geth itself creates
        // `<datadir>/geth/`, so --datadir must be a sibling subdirectory.
        val executionRoot = layout?.roles?.firstOrNull { it.id == "execution" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val executionDatadir = if (executionRoot.isNotEmpty())
        {
            "$executionRoot/datadir"
        }
        else
        {
            ""
        }
        val consensus = layout?.roles?.firstOrNull { it.id == "consensus" }?.dir?.trim().orEmpty()
        val args = mutableListOf(
            "--toolkit-env=$env",
            "--toolkit-archive=${if (archive) "1" else "0"}",
            "--toolkit-chain-id=${cluster.chainId}",
        )
        if (executionDatadir.isNotEmpty())
        {
            args += "--toolkit-execution=$executionDatadir"
        }
        if (consensus.isNotEmpty())
        {
            args += "--toolkit-consensus=$consensus"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "geth",
                args = args,
                extractArchiveGlob = "geth-linux-*.tar.gz",
                normalizeDir = null,
            ),
            height = NodeHeightSpec(
                kind = "eth_rpc",
                portRole = "http",
            ),
        )
    }
}
