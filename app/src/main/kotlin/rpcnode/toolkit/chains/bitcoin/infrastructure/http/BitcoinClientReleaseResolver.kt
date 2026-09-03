package rpcnode.toolkit.chains.bitcoin.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.bitcore.infrastructure.BitcoreChainSpecs
import rpcnode.toolkit.chains.bitcore.infrastructure.BitcoreClientReleaseResolver
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/** Bitcoin Core latest release from GitHub. */
class BitcoinClientReleaseResolver(
    private val github: GitHubReleaseClient,
) : ClientReleaseResolver
{
    private val inner = BitcoreClientReleaseResolver(BitcoreChainSpecs.BITCOIN, github)

    override suspend fun resolve(env: EnvId): ClientRelease? = inner.resolve(env)
}
