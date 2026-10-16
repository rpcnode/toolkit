package rpcnode.toolkit.chains.stacks.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmReleases
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/**
 * Highest final `stacks-network/stacks-core` release (tags `4.0.5`, `3.4.0.0.4` — up to five numeric parts; the repo also
 * tags `signer-3.4.0.0.4.0`, which parses as nothing) that carries the Linux glibc x64 zip. One zip serves mainnet and testnet.
 */
class StacksClientReleaseResolver(
    private val github: GitHubReleaseClient,
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        if (env.value != "mainnet" && env.value != "testnet") return null
        val release = NonEvmReleases.highest(github.releases(REPO), requiredAssets = { listOf(ASSET) }) ?: return null
        return ClientRelease(version = release.version, tag = release.tag, sourceLabel = REPO)
    }

    companion object
    {
        const val REPO = "stacks-network/stacks-core"
        const val ASSET = "linux-glibc-x64.zip"
    }
}
