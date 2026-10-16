package rpcnode.toolkit.chains.robinhood.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/**
 * Robinhood Chain runs stock Nitro, so both envs follow the Arbitrum resolver's release (the Docker Hub tag of the
 * newest OffchainLabs/nitro release); [nitro] is that resolver.
 */
class RobinhoodClientReleaseResolver(
    private val nitro: ClientReleaseResolver,
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        if (env != EnvId.MAINNET && env != EnvId.TESTNET)
        {
            return null
        }
        return nitro.resolve(EnvId.MAINNET)
    }
}
