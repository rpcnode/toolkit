package rpcnode.toolkit.chains.cometbft.infrastructure

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.cometbft.infrastructure.http.CometbftClientReleaseResolver
import rpcnode.toolkit.chains.cometbft.infrastructure.http.CometbftNetworkTipProbe
import rpcnode.toolkit.chains.cometbft.infrastructure.http.CometbftSnapshotResolver
import rpcnode.toolkit.chains.cometbft.infrastructure.start.CometbftNodeStart
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe

/** Maps every shipped CometBFT network onto the shared classes; `Toolkit` adds these maps to its own. */
object CometbftWiring
{
    private val repos = mapOf(
        NetworkId.COSMOS to "cosmos/gaia",
        NetworkId.OSMOSIS to "osmosis-labs/osmosis",
        NetworkId.INJECTIVE to "InjectiveFoundation/injective-core",
        NetworkId.SEI to "sei-protocol/sei-chain",
        NetworkId.CELESTIA to "celestiaorg/celestia-app",
        NetworkId.XRPLEVM to "xrplevm/node",
    )

    /** Testnets that run a release candidate of the same repo (Osmosis `osmo-test-5` ran v31.0.0-rc1 while mainnet was v31.0.3). */
    private val prereleaseTestnets = setOf(NetworkId.OSMOSIS)

    fun clientReleaseResolvers(github: GitHubReleaseClient): Map<NetworkId, ClientReleaseResolver> =
        CometbftProfiles.ALL.associate { p ->
            p.network to CometbftClientReleaseResolver(
                github = github,
                repo = checkNotNull(repos[p.network]) { "no repo for ${p.network}" },
                envs = p.envs.mapNotNull { EnvId.parse(it.id) }.toSet(),
                includePrerelease = { env -> env.value == "testnet" && p.network in prereleaseTestnets },
            )
        }

    fun tipProbes(): Map<NetworkId, NetworkTipProbe> =
        CometbftProfiles.ALL.associate { it.network to CometbftNetworkTipProbe() }

    fun chainStarts(): Map<NetworkId, ChainNodeStart> =
        CometbftProfiles.ALL.associate { it.network to CometbftNodeStart(it) }

    fun snapshotResolvers(): Map<NetworkId, SnapshotResolver> =
        CometbftProfiles.ALL.associate { it.network to CometbftSnapshotResolver(CometbftProfiles.snapshotSources(it)) }
}
