package rpcnode.toolkit.chains.etc.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.etc.infrastructure.EtcClusters
import rpcnode.toolkit.chains.etc.infrastructure.EtcHostBinaries
import rpcnode.toolkit.chains.etc.infrastructure.EtcUnitBodies
import rpcnode.toolkit.chains.etc.infrastructure.etcNodeMode
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * Ethereum Classic core-geth (proof of work, no consensus client). Launch args encode env, mode and the chaindata
 * disk role for [rpcnode.toolkit.chains.etc.infrastructure.proc.EtcNodeProcessStarter].
 */
class EtcNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.ETC

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = ctx.env.trim().lowercase().ifEmpty { "mainnet" }
        val cluster = EtcClusters.lookup(env)
        val archive = etcNodeMode(ctx.installOptionsJson) == "archive"
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val chaindata = layout?.roles?.firstOrNull { it.id == "chaindata" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val args = mutableListOf(
            "--toolkit-env=$env",
            "--toolkit-archive=${if (archive) "1" else "0"}",
            "--toolkit-chain-id=${cluster.chainId}",
        )
        if (chaindata.isNotEmpty())
        {
            args += "--toolkit-datadir=$chaindata/${EtcUnitBodies.DATADIR}"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "bin/${EtcHostBinaries.NODE}",
                args = args,
                extractArchiveGlob = null,
                normalizeDir = null,
                logFile = EtcUnitBodies.RELATIVE_LOG,
            ),
            height = NodeHeightSpec(
                kind = "eth_rpc",
                portRole = "http",
            ),
        )
    }
}
