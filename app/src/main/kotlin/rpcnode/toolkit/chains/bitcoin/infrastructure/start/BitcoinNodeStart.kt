package rpcnode.toolkit.chains.bitcoin.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.bitcore.infrastructure.BitcoreChainSpecs
import rpcnode.toolkit.chains.bitcore.infrastructure.BitcoreNodeStartPlan
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan

/** Bitcoin Core — see [BitcoreChainSpecs.BITCOIN]. */
class BitcoinNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.BITCOIN

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan =
        BitcoreNodeStartPlan.plan(BitcoreChainSpecs.BITCOIN, ctx)
}
