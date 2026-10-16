package rpcnode.toolkit.chains.cometbft.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.cometbft.infrastructure.CometbftProfile
import rpcnode.toolkit.chains.cometbft.infrastructure.CometbftUnitBodies
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/** One class for every CometBFT chain: the profile says which. Launch args carry the env and the `home` disk role. */
class CometbftNodeStart(private val profile: CometbftProfile) : ChainNodeStart
{
    override val networkId: NetworkId = profile.network

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = profile.env(ctx.env)
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val home = layout?.roles?.firstOrNull { it.id == "home" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim()?.let { "$it/home" }.orEmpty() }
        val args = mutableListOf("--toolkit-env=${env.id}")
        if (home.isNotEmpty())
        {
            args += "--toolkit-home=$home"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "bin/${profile.binary}",
                args = args,
                extractArchiveGlob = null,
                normalizeDir = null,
                logFile = CometbftUnitBodies.relativeLog(profile),
            ),
            height = NodeHeightSpec(kind = "cometbft_status", portRole = profile.rpcRole),
        )
    }
}
