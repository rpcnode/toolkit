package rpcnode.toolkit.chains.base.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.base.infrastructure.BaseClusters
import rpcnode.toolkit.chains.base.infrastructure.baseL1FromInstallOptions
import rpcnode.toolkit.chains.base.infrastructure.baseSnapshotFlavor
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * Base OP Stack (base-reth-node + base-consensus). Launch args encode env + disk
 * roles for [rpcnode.toolkit.chains.base.infrastructure.proc.BaseNodeProcessStarter].
 */
class BaseNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.BASE

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = ctx.env.trim().lowercase().ifEmpty { "mainnet" }
        val cluster = BaseClusters.lookup(env)
        val flavor = baseSnapshotFlavor(ctx.installOptionsJson)
        val l1 = baseL1FromInstallOptions(ctx.installOptionsJson, cluster.env)
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val execution = layout?.roles?.firstOrNull { it.id == "execution" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val snapshots = layout?.roles?.firstOrNull { it.id == "snapshots" }?.dir?.trim().orEmpty()
        val args = mutableListOf(
            "--toolkit-env=${cluster.env}",
            "--toolkit-chain-id=${cluster.chainId}",
            "--toolkit-snapshot=$flavor",
            "--toolkit-l1-rpc=${l1.rpc}",
            "--toolkit-l1-beacon=${l1.beacon}",
        )
        if (execution.isNotEmpty())
        {
            args += "--toolkit-execution=$execution"
        }
        if (snapshots.isNotEmpty())
        {
            args += "--toolkit-snapshots=$snapshots"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "base-reth-node",
                args = args,
                extractArchiveGlob = "base-reth-node-*.tar.gz",
                normalizeDir = null,
            ),
            height = NodeHeightSpec(
                kind = "eth_rpc",
                portRole = "http",
            ),
        )
    }
}
