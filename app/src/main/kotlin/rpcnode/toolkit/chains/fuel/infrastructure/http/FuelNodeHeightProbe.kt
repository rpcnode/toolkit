package rpcnode.toolkit.chains.fuel.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.catalog.domain.EnvId
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

/** `{ chain { latestBlock { height } } }` → `data.chain.latestBlock.height` (a numeric string) — node and public GraphQL alike. */
object FuelGraphql
{
    val HEIGHT = HeightReader.GraphQl(
        localPath = "/v1/graphql",
        query = "{ chain { latestBlock { height } } }",
        field = listOf("chain", "latestBlock", "height"),
    )
}

class FuelNodeHeightProbe(
    http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
) : HostNodeHeightProbe by ReaderHeightProbe(FuelGraphql.HEIGHT, http)

class FuelNetworkTipProbe(
    http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(8))),
) : NetworkTipProbe by ReaderTipProbe(FuelGraphql.HEIGHT, http)

/**
 * Highest final `v*` release of FuelLabs/fuel-core that carries both Linux tarballs. By version, not date (`v0.47.4` was
 * published after `v0.48.0`); the `testnet-v*` prereleases have no assets and do not parse as `v<number>`.
 */
class FuelClientReleaseResolver(
    private val github: GitHubReleaseClient,
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        if (env.value != "mainnet" && env.value != "testnet") return null
        val release = NonEvmReleases.highest(github.releases(REPO), prefix = "v", requiredAssets = { assets(it.version) }) ?: return null
        return ClientRelease(version = release.version, tag = release.tag, sourceLabel = REPO)
    }

    companion object
    {
        const val REPO = "FuelLabs/fuel-core"
        fun assets(version: String) = listOf(
            "fuel-core-$version-x86_64-unknown-linux-gnu.tar.gz",
            "fuel-core-$version-aarch64-unknown-linux-gnu.tar.gz",
        )
    }
}
