package rpcnode.toolkit.chains.near.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.near.infrastructure.NearChains
import rpcnode.toolkit.chains.near.infrastructure.NearSettings
import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmStartPlan
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan

/** NEAR: the home of `neard` is the `state` disk role (the node dir on a single-disk host). */
class NearNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.NEAR

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = NearChains.env(ctx.env)
        val args = mutableListOf("--toolkit-env=${env.id}")
        NonEvmStartPlan.roleDir(ctx, "state").takeIf { it.isNotEmpty() }?.let { args += "--toolkit-state=$it" }
        return NonEvmStartPlan.plan(
            entry = "bin/neard",
            logFile = NearSettings.RELATIVE_LOG,
            heightKind = "near_status",
            args = args,
        )
    }
}
