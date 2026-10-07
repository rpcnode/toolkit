package rpcnode.toolkit.chains.bitcore.infrastructure

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan

/** ChainNodeStart wired from [BitcoreChainSpec] — one instance per UTXO fork network. */
class SpecBitcoreNodeStart(
    private val spec: BitcoreChainSpec,
) : ChainNodeStart
{
    override val networkId: NetworkId = spec.networkId

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan =
        BitcoreNodeStartPlan.plan(spec, ctx)
}
