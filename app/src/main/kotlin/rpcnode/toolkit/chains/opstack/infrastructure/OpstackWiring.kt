package rpcnode.toolkit.chains.opstack.infrastructure

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.opstack.infrastructure.http.OpstackClientReleaseResolver
import rpcnode.toolkit.chains.opstack.infrastructure.http.OpstackNetworkTipProbe
import rpcnode.toolkit.chains.opstack.infrastructure.start.OpstackNodeStart
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.nodes.application.start.ChainNodeStart

/** Maps every shipped OP Stack network onto the shared classes; `Toolkit` adds these maps to its own. */
object OpstackWiring
{
    /** Only networks whose EL has a GitHub release (World Chain): op-reth / op-node are pinned and need no resolver. */
    fun clientReleaseResolvers(github: GitHubReleaseClient): Map<NetworkId, ClientReleaseResolver> =
        OpstackNetworks.ALL
            .filter { it.elFlavor == OpstackElFlavor.WORLD_CHAIN }
            .associate { it.network to OpstackClientReleaseResolver(github) }

    fun tipProbes(): Map<NetworkId, NetworkTipProbe> =
        OpstackNetworks.ALL.associate { it.network to OpstackNetworkTipProbe() }

    fun chainStarts(): Map<NetworkId, ChainNodeStart> =
        OpstackNetworks.ALL.associate { it.network to OpstackNodeStart(it) }
}
