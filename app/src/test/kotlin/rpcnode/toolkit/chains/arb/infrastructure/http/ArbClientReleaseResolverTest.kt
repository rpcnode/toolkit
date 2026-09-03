package rpcnode.toolkit.chains.arb.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.FakeGitHubReleaseClient
import rpcnode.toolkit.clients.application.GitHubRelease

class ArbClientReleaseResolverTest
{
    @Test
    fun resolve_uses_docker_tag_from_release_body() = runTest {
        val github = FakeGitHubReleaseClient(
            mapOf(
                "OffchainLabs/nitro" to GitHubRelease(
                    tag = "v3.11.3",
                    version = "3.11.3",
                    body = "Image: `offchainlabs/nitro-node:v3.11.3-beb2108`",
                ),
            ),
        )
        val hub = ArbNitroDockerHubTags { emptyList() }
        val release = ArbClientReleaseResolver(github, hub).resolve(EnvId.MAINNET)
        assertEquals("3.11.3", release?.version)
        assertEquals("v3.11.3-beb2108", release?.tag)
        assertEquals("OffchainLabs/nitro", release?.sourceLabel)
    }

    @Test
    fun resolve_falls_back_to_hub_tags() = runTest {
        val github = FakeGitHubReleaseClient(
            mapOf(
                "OffchainLabs/nitro" to GitHubRelease(tag = "v3.11.3", version = "3.11.3", body = ""),
            ),
        )
        val hub = ArbNitroDockerHubTags {
            listOf("v3.11.3-beb2108-slim", "v3.11.3-beb2108", "v3.11.3-beb2108-amd64")
        }
        val release = ArbClientReleaseResolver(github, hub).resolve(EnvId.MAINNET)
        assertEquals("v3.11.3-beb2108", release?.tag)
    }

    @Test
    fun resolve_null_when_no_docker_tag() = runTest {
        val github = FakeGitHubReleaseClient(
            mapOf("OffchainLabs/nitro" to GitHubRelease(tag = "v3.11.3", version = "3.11.3")),
        )
        val hub = ArbNitroDockerHubTags { emptyList() }
        assertNull(ArbClientReleaseResolver(github, hub).resolve(EnvId.MAINNET))
    }
}
