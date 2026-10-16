package rpcnode.toolkit.chains.stellar.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.stellar.infrastructure.StellarClusters
import rpcnode.toolkit.chains.stellar.infrastructure.StellarHistory
import rpcnode.toolkit.chains.stellar.infrastructure.StellarUnitBodies
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/** Stellar: stellar-rpc with its embedded captive stellar-core. Launch args carry env, history window and the blockchain role. */
class StellarNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.STELLAR

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = StellarClusters.normalizeEnv(ctx.env)
        val history = StellarHistory.fromJson(ctx.installOptionsJson).mode
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val blockchain = layout?.roles?.firstOrNull { it.id == "blockchain" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val args = mutableListOf("--toolkit-env=$env", "--toolkit-history=$history")
        if (blockchain.isNotEmpty())
        {
            args += "--toolkit-blockchain=$blockchain"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = StellarUnitBodies.RPC_BIN,
                args = args,
                extractArchiveGlob = null,
                normalizeDir = null,
                logFile = StellarUnitBodies.RELATIVE_LOG,
            ),
            height = NodeHeightSpec(
                kind = "stellar_rpc",
                portRole = "http",
            ),
        )
    }
}
