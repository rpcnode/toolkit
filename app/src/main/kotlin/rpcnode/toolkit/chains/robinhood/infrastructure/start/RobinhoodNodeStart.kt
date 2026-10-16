package rpcnode.toolkit.chains.robinhood.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.arb.infrastructure.arbL1FromInstallOptions
import rpcnode.toolkit.chains.arb.infrastructure.arbSnapshotFlavor
import rpcnode.toolkit.chains.robinhood.infrastructure.RobinhoodClusters
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * Robinhood Chain Nitro full node. Launch args encode env + disk role + snapshot flavor + L1 parent for
 * [rpcnode.toolkit.chains.robinhood.infrastructure.proc.RobinhoodNodeProcessStarter].
 * The install options (`snapshot`, `l1_rpc`, `l1_beacon`) are the same as Arbitrum's.
 */
class RobinhoodNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.ROBINHOOD

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = RobinhoodClusters.normalizeEnv(ctx.env)
        val snapshot = RobinhoodClusters.normalizeSnapshot(arbSnapshotFlavor(ctx.installOptionsJson))
        val l1 = arbL1FromInstallOptions(ctx.installOptionsJson, env)
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val execution = layout?.roles?.firstOrNull { it.id == "execution" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val args = mutableListOf(
            "--toolkit-env=$env",
            "--toolkit-snapshot=$snapshot",
            "--toolkit-l1-rpc=${l1.rpc}",
            "--toolkit-l1-beacon=${l1.beacon}",
        )
        if (execution.isNotEmpty())
        {
            args += "--toolkit-execution=$execution"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "nitro",
                args = args,
                extractArchiveGlob = "nitro-*.tar.gz",
                normalizeDir = null,
            ),
            height = NodeHeightSpec(
                kind = "eth_rpc",
                portRole = "http",
            ),
        )
    }
}
