package rpcnode.toolkit.chains.aleo.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.aleo.infrastructure.AleoChains
import rpcnode.toolkit.chains.nonevm.infrastructure.HeightReader
import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmReleases
import rpcnode.toolkit.chains.nonevm.infrastructure.ReaderHeightProbe
import rpcnode.toolkit.chains.nonevm.infrastructure.ReaderTipProbe
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** The REST body is the bare block height (`22782109`) on the node and on api.explorer.provable.com. */
object AleoHeight
{
    val READER = HeightReader.GetPlain(AleoChains.LOCAL_HEIGHT_PATH)
}

class AleoNodeHeightProbe(
    http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
) : HostNodeHeightProbe by ReaderHeightProbe(AleoHeight.READER, http)

class AleoNetworkTipProbe(
    http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(8))),
) : NetworkTipProbe by ReaderTipProbe(AleoHeight.READER, http)

/**
 * Newest final `v*` release of ProvableHQ/snarkOS that carries the Linux x86_64 zip (releases are not all alike: `v4.12.0`
 * has three assets, `v4.9.0`'s sibling `testnet-v4.9.0` none). The `testnet-v*` line is not resolved: DEFERRED.
 */
class AleoClientReleaseResolver(
    private val github: GitHubReleaseClient,
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        if (env.value != AleoChains.ENV) return null
        val release = NonEvmReleases.highest(github.releases(REPO), prefix = "v", requiredAssets = { listOf(asset(it.tag)) }) ?: return null
        return ClientRelease(version = release.version, tag = release.tag, sourceLabel = REPO)
    }

    companion object
    {
        const val REPO = "ProvableHQ/snarkOS"
        fun asset(tag: String) = "aleo-$tag-x86_64-unknown-linux-gnu.zip"
    }
}
