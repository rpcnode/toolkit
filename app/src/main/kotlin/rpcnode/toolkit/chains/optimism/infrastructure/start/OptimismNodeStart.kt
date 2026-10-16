package rpcnode.toolkit.chains.optimism.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.arb.infrastructure.arbL1FromInstallOptions
import rpcnode.toolkit.chains.optimism.infrastructure.OptimismClusters
import rpcnode.toolkit.chains.optimism.infrastructure.OptimismNodeMode
import rpcnode.toolkit.chains.optimism.infrastructure.OptimismUnitBodies
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * Optimism op-reth + op-node. Launch args encode env, node type, the disk roles and the L1 parent for
 * [rpcnode.toolkit.chains.optimism.infrastructure.proc.OptimismNodeProcessStarter]. The L1 install options
 * (`l1_rpc`, `l1_beacon`) are the same as Arbitrum's.
 */
class OptimismNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.OPTIMISM

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = OptimismClusters.normalizeEnv(ctx.env)
        val mode = OptimismNodeMode.fromJson(ctx.installOptionsJson)
        val l1 = arbL1FromInstallOptions(ctx.installOptionsJson, env)
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        fun role(id: String) = layout?.roles?.firstOrNull { it.id == id }?.dir?.trim().orEmpty()
        val execution = role("execution").ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val args = mutableListOf(
            "--toolkit-env=$env",
            "--toolkit-mode=$mode",
            "--toolkit-l1-rpc=${l1.rpc}",
            "--toolkit-l1-beacon=${l1.beacon}",
        )
        if (execution.isNotEmpty())
        {
            args += "--toolkit-execution=$execution"
        }
        role("consensus").takeIf { it.isNotEmpty() }?.let { args += "--toolkit-consensus=$it" }
        role("snapshots").takeIf { it.isNotEmpty() }?.let { args += "--toolkit-snapshots=$it" }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = OptimismUnitBodies.RUN_SCRIPT,
                args = args,
                normalizeDir = null,
                logFile = OptimismUnitBodies.RELATIVE_LOG,
            ),
            height = NodeHeightSpec(
                kind = "eth_rpc",
                portRole = "http",
            ),
        )
    }
}
