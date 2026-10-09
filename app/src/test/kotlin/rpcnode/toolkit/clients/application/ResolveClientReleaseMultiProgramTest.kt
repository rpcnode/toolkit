package rpcnode.toolkit.clients.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientArtifactRole
import rpcnode.toolkit.clients.domain.model.ClientArtifactSpec
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.model.ClientRelease
import rpcnode.toolkit.clients.domain.model.ClientVersionSource

class ResolveClientReleaseMultiProgramTest
{
    @Test
    fun network_resolver_does_not_override_other_program_github_repo() = runTest {
        val github = GitHubReleaseClient { repo, _ ->
            when (repo)
            {
                "ethereum/go-ethereum" -> GitHubRelease(tag = "v1.17.5", version = "1.17.5")
                "sigp/lighthouse" -> GitHubRelease(tag = "v8.2.2", version = "8.2.2")
                else -> null
            }
        }
        val resolvers = mapOf(
            NetworkId.ETHEREUM to ClientReleaseResolver {
                ClientRelease(version = "1.17.5", tag = "v1.17.5", sourceLabel = "ethereum/go-ethereum")
            },
        )
        val geth = program("geth", "ethereum/go-ethereum")
        val lighthouse = program("lighthouse", "sigp/lighthouse")

        val gethVer = resolveClientRelease(geth, github, resolvers)
        assertEquals("1.17.5", gethVer.version)
        assertEquals("ethereum/go-ethereum", gethVer.sourceLabel)

        val lhVer = resolveClientRelease(lighthouse, github, resolvers)
        assertEquals("8.2.2", lhVer.version)
        assertEquals("sigp/lighthouse", lhVer.sourceLabel)
    }

    private fun program(id: String, repo: String) = ClientProgramSpec(
        network = NetworkId.ETHEREUM,
        env = EnvId.MAINNET,
        programId = id,
        source = ClientVersionSource.GitHubRelease(repo),
        artifacts = listOf(
            ClientArtifactSpec(name = "$id.tar.gz", role = ClientArtifactRole.ARTIFACT, urlTemplate = "https://example/{tag}"),
        ),
    )
}
