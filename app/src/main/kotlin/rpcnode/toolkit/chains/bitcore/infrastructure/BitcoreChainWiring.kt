package rpcnode.toolkit.chains.bitcore.infrastructure

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.bitcoin.infrastructure.http.BitcoinNetworkTipProbe
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.nodes.application.start.ChainNodeStart

/** Panel wiring for all bitcoind-style networks. */
object BitcoreChainWiring
{
    fun chainStarts(): Map<NetworkId, ChainNodeStart> =
        BitcoreChainSpecs.ALL.associate { spec ->
            spec.networkId to SpecBitcoreNodeStart(spec)
        }

    fun clientReleaseResolvers(github: GitHubReleaseClient): Map<NetworkId, ClientReleaseResolver> =
        BitcoreChainSpecs.ALL.associate { spec ->
            spec.networkId to BitcoreClientReleaseResolver(spec, github)
        }

    fun tipProbes(blockchair: BlockchairStatsTipProbe = BlockchairStatsTipProbe()): Map<NetworkId, NetworkTipProbe> =
        mapOf(
            NetworkId.BITCOIN to BitcoinNetworkTipProbe(),
            NetworkId.LTC to blockchair,
            NetworkId.DOGE to blockchair,
            NetworkId.DASH to blockchair,
            NetworkId.BCH to blockchair,
        )
}
