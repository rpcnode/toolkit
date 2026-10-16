package rpcnode.toolkit.chains.stellar.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/** The Stellar client pin follows the newest stable `stellar-rpc` package of the SDF apt repository. */
class StellarClientReleaseResolver(
    private val apt: StellarAptIndex,
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        if (env != EnvId.MAINNET && env != EnvId.TESTNET)
        {
            return null
        }
        val pkg = apt.newest("stellar-rpc") ?: return null
        val version = pkg.version.substringBefore('-')
        return ClientRelease(version = version, tag = "v$version", sourceLabel = REPO)
    }

    companion object
    {
        const val REPO = "stellar/stellar-rpc"
    }
}
