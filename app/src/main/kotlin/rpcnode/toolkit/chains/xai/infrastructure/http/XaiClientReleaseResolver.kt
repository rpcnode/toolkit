package rpcnode.toolkit.chains.xai.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/**
 * Xai runs stock Nitro, so it follows the Arbitrum resolver's release (the Docker Hub tag of the newest
 * OffchainLabs/nitro release); [nitro] is that resolver.
 */
class XaiClientReleaseResolver(
    private val nitro: ClientReleaseResolver,
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        if (env != EnvId.MAINNET)
        {
            return null
        }
        return nitro.resolve(EnvId.MAINNET)
    }
}
