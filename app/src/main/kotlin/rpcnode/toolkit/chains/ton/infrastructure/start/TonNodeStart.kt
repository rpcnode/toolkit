package rpcnode.toolkit.chains.ton.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.ton.infrastructure.TonClusters
import rpcnode.toolkit.chains.ton.infrastructure.TonInstallOptions
import rpcnode.toolkit.chains.ton.infrastructure.TonUnitBodies
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * Toncoin MyTonCtrl liteserver. Launch args encode env + disks + history for
 * [rpcnode.toolkit.chains.ton.infrastructure.proc.TonNodeProcessStarter].
 */
class TonNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.TON

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = TonClusters.normalizeEnv(ctx.env)
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val blockchain = layout?.roles?.firstOrNull { it.id == "blockchain" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val archive = layout?.roles?.firstOrNull { it.id == "archive" }?.dir?.trim().orEmpty()
        val history = TonInstallOptions.fromJson(ctx.installOptionsJson)
        val args = mutableListOf(
            "--toolkit-env=$env",
            "--toolkit-history=$history",
        )
        if (blockchain.isNotEmpty())
        {
            args += "--toolkit-blockchain=$blockchain"
        }
        if (archive.isNotEmpty())
        {
            args += "--toolkit-archive=$archive"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = TonUnitBodies.NODE_START_SCRIPT,
                args = args,
                logFile = TonUnitBodies.RELATIVE_LOG,
            ),
            height = NodeHeightSpec(
                kind = "ton_http",
                portRole = "http",
            ),
        )
    }
}
