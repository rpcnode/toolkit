package rpcnode.toolkit.chains.fuel.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.fuel.infrastructure.FuelChains
import rpcnode.toolkit.chains.fuel.infrastructure.FuelSettings
import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmStartPlan
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan

/** Fuel: the `state` disk role holds `--db-path`; the Ethereum RPC for the relayer is the `l1_rpc` wizard option. */
class FuelNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.FUEL

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = FuelChains.env(ctx.env)
        val args = mutableListOf("--toolkit-env=${env.id}")
        NonEvmStartPlan.roleDir(ctx, "state").takeIf { it.isNotEmpty() }?.let { args += "--toolkit-state=$it" }
        NonEvmStartPlan.option(ctx.installOptionsJson, "l1_rpc")?.let { args += "--toolkit-relayer=$it" }
        return NonEvmStartPlan.plan(
            entry = "bin/fuel-core",
            logFile = FuelSettings.RELATIVE_LOG,
            heightKind = "fuel_graphql",
            args = args,
        )
    }
}
