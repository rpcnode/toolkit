package rpcnode.toolkit.chains.aleo.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.aleo.infrastructure.AleoSettings
import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmStartPlan
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan

/** Aleo: the `ledger` and `nodedata` disk roles become `--ledger-storage` / `--node-data-storage`. */
class AleoNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.ALEO

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val args = mutableListOf("--toolkit-env=mainnet")
        NonEvmStartPlan.roleDir(ctx, "ledger").takeIf { it.isNotEmpty() }?.let { args += "--toolkit-ledger=$it" }
        NonEvmStartPlan.roleDir(ctx, "nodedata").takeIf { it.isNotEmpty() && it != NonEvmStartPlan.roleDir(ctx, "ledger") }
            ?.let { args += "--toolkit-nodedata=$it" }
        return NonEvmStartPlan.plan(
            entry = "bin/snarkos",
            logFile = AleoSettings.RELATIVE_LOG,
            heightKind = "aleo_rest",
            args = args,
        )
    }
}
