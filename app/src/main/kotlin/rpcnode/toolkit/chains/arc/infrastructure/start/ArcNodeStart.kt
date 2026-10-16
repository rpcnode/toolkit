package rpcnode.toolkit.chains.arc.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.arc.infrastructure.ArcClusters
import rpcnode.toolkit.chains.arc.infrastructure.ArcHostBinaries
import rpcnode.toolkit.chains.arc.infrastructure.ArcNodeMode
import rpcnode.toolkit.chains.arc.infrastructure.ArcUnitBodies
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * Circle Arc node. Launch args encode env + node type + the execution disk role for
 * [rpcnode.toolkit.chains.arc.infrastructure.proc.ArcNodeProcessStarter].
 */
class ArcNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.ARC

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = ArcClusters.normalizeEnv(ctx.env)
        val mode = ArcNodeMode.fromJson(ctx.installOptionsJson)
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val execution = layout?.roles?.firstOrNull { it.id == "execution" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val args = mutableListOf("--toolkit-env=$env", "--toolkit-mode=$mode")
        if (execution.isNotEmpty())
        {
            args += "--toolkit-execution=$execution"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "bin/${ArcHostBinaries.EXECUTION}",
                args = args,
                extractArchiveGlob = "arc-node-linux-*.tar.gz",
                normalizeDir = null,
                logFile = ArcUnitBodies.RELATIVE_LOG,
            ),
            height = NodeHeightSpec(
                kind = "eth_rpc",
                portRole = "http",
            ),
        )
    }
}
