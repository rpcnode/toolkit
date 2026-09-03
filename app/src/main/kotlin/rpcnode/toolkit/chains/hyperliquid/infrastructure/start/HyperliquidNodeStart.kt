package rpcnode.toolkit.chains.hyperliquid.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.hyperliquid.infrastructure.HyperliquidClusters
import rpcnode.toolkit.chains.hyperliquid.infrastructure.HyperliquidHostBinaries
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * Hyperliquid non-validator. Launch args encode env + chain data dir for
 * [rpcnode.toolkit.chains.hyperliquid.infrastructure.proc.HyperliquidNodeProcessStarter].
 */
class HyperliquidNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.HYPERLIQUID

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = HyperliquidClusters.normalizeEnv(ctx.env)
        val cluster = HyperliquidClusters.lookup(env)
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val chain = layout?.roles?.firstOrNull { it.id == "chain" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val args = mutableListOf(
            "--toolkit-env=$env",
            "--toolkit-chain-id=${cluster.chainId}",
        )
        if (chain.isNotEmpty())
        {
            args += "--toolkit-chain=$chain"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "bin/${HyperliquidHostBinaries.BINARY}",
                args = args,
                extractArchiveGlob = null,
                normalizeDir = null,
                logFile = "logs/hl-visor.log",
            ),
            height = NodeHeightSpec(
                kind = "eth_rpc",
                portRole = "http",
            ),
        )
    }
}
