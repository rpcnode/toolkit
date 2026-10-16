package rpcnode.toolkit.chains.evmfamily.infrastructure

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.evmfamily.infrastructure.http.EvmNetworkTipProbe
import rpcnode.toolkit.chains.evmfamily.infrastructure.start.EvmNodeStart
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientArtifactUrlResolver
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.nodes.application.start.ChainNodeStart

/** Maps every shipped EVM-family network onto the shared classes; `Toolkit` adds these maps to its own. */
object EvmWiring
{
    fun tipProbes(): Map<NetworkId, NetworkTipProbe> =
        EvmProfiles.ALL.associate { it.network to EvmNetworkTipProbe() }

    fun chainStarts(): Map<NetworkId, ChainNodeStart> =
        EvmProfiles.ALL.associate { it.network to EvmNodeStart(it) }

    fun snapshotResolvers(): Map<NetworkId, SnapshotResolver> =
        EvmProfiles.ALL.mapNotNull { p -> p.snapshot?.let { p.network to it() } }.toMap()

    /** Per-network artifact URL rules (clients.yml entries that are not plain `{tag}` templates). */
    fun artifactUrlResolvers(github: GitHubReleaseClient): Map<NetworkId, ClientArtifactUrlResolver> =
        EvmProfiles.ALL.mapNotNull { p -> p.artifactUrls?.let { p.network to it(github) } }.toMap()
}
