package rpcnode.toolkit.chains.sui.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.sui.infrastructure.SuiClusters
import rpcnode.toolkit.chains.sui.infrastructure.SuiHostBinaries
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * Sui fullnode. Launch args encode env + disk roles for
 * [rpcnode.toolkit.chains.sui.infrastructure.proc.SuiNodeProcessStarter].
 */
class SuiNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.SUI

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = SuiClusters.normalizeEnv(ctx.env)
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val state = layout?.roles?.firstOrNull { it.id == "state" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val index = layout?.roles?.firstOrNull { it.id == "index" }?.dir?.trim().orEmpty()
        val args = mutableListOf("--toolkit-env=$env")
        if (state.isNotEmpty())
        {
            args += "--toolkit-state=$state"
        }
        if (index.isNotEmpty())
        {
            args += "--toolkit-index=$index"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "bin/${SuiHostBinaries.NODE}",
                args = args,
                extractArchiveGlob = "sui-*.tgz",
                normalizeDir = null,
                logFile = "logs/sui.log",
            ),
            height = NodeHeightSpec(
                kind = "sui_rpc",
                portRole = "http",
            ),
        )
    }
}
