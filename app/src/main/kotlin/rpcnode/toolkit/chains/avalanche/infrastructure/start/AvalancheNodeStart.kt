package rpcnode.toolkit.chains.avalanche.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.avalanche.infrastructure.AvalancheClusters
import rpcnode.toolkit.chains.avalanche.infrastructure.AvalancheHostBinaries
import rpcnode.toolkit.chains.avalanche.infrastructure.AvalancheNodeMode
import rpcnode.toolkit.chains.avalanche.infrastructure.AvalancheUnitBodies
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * Avalanche avalanchego (P/X/C). Launch args encode env + C-Chain mode + the blockchain disk role for
 * [rpcnode.toolkit.chains.avalanche.infrastructure.proc.AvalancheNodeProcessStarter].
 */
class AvalancheNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.AVALANCHE

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = AvalancheClusters.normalizeEnv(ctx.env)
        val mode = AvalancheNodeMode.fromJson(ctx.installOptionsJson)
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val blockchain = layout?.roles?.firstOrNull { it.id == "blockchain" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val args = mutableListOf("--toolkit-env=$env", "--toolkit-mode=$mode")
        if (blockchain.isNotEmpty())
        {
            args += "--toolkit-blockchain=$blockchain"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "bin/${AvalancheHostBinaries.NODE}",
                args = args,
                extractArchiveGlob = "avalanchego-linux-*.tar.gz",
                normalizeDir = null,
                logFile = AvalancheUnitBodies.RELATIVE_LOG,
            ),
            height = NodeHeightSpec(
                kind = "eth_rpc",
                portRole = "http",
            ),
        )
    }
}
