package rpcnode.toolkit.chains.bsc.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.bsc.infrastructure.BscClusters
import rpcnode.toolkit.chains.bsc.infrastructure.bscSnapshotFlavor
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * BSC geth (Parlia). Launch args encode env + disk roles for
 * [rpcnode.toolkit.chains.bsc.infrastructure.proc.BscNodeProcessStarter].
 * Entry is the geth binary path relative to node_dir after sync.
 */
class BscNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.BSC

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = ctx.env.trim().lowercase().ifEmpty { "mainnet" }
        val cluster = BscClusters.lookup(env)
        val flavor = bscSnapshotFlavor(ctx.installOptionsJson)
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val chaindata = layout?.roles?.firstOrNull { it.id == "chaindata" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val snapshots = layout?.roles?.firstOrNull { it.id == "snapshots" }?.dir?.trim().orEmpty()
        val args = mutableListOf(
            "--toolkit-env=${cluster.env}",
            "--toolkit-chain-id=${cluster.chainId}",
            "--toolkit-snapshot=$flavor",
        )
        if (chaindata.isNotEmpty())
        {
            args += "--toolkit-datadir=$chaindata"
        }
        if (snapshots.isNotEmpty())
        {
            args += "--toolkit-snapshots=$snapshots"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "geth",
                args = args,
                extractArchiveGlob = null,
                normalizeDir = null,
            ),
            height = NodeHeightSpec(
                kind = "eth_rpc",
                portRole = "http",
            ),
        )
    }
}
