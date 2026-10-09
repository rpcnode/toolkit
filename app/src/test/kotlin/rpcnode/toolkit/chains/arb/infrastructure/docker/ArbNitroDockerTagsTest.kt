package rpcnode.toolkit.chains.arb.infrastructure.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArbNitroDockerTagsTest
{
    @Test
    fun fromReleaseBody_picks_canonical_full_node_tag()
    {
        val body = """
            This release is available as a Docker Image on Docker Hub at `offchainlabs/nitro-node:v3.11.3-beb2108`
            Use offchainlabs/nitro-node:v3.11.3-beb2108-validator for validators.
            """.trimIndent()
        assertEquals("v3.11.3-beb2108", ArbNitroDockerTags.fromReleaseBody(body))
    }

    @Test
    fun pickCanonical_filters_hub_variants()
    {
        val tags = listOf(
            "v3.11.3-beb2108-slim",
            "v3.11.3-beb2108-amd64",
            "v3.11.3-beb2108",
            "v3.11.3-beb2108-validator",
        )
        assertEquals("v3.11.3-beb2108", ArbNitroDockerTags.pickCanonical(tags, "v3.11.3"))
    }

    @Test
    fun isCanonicalFullNodeTag_rejects_variants()
    {
        assertTrue(ArbNitroDockerTags.isCanonicalFullNodeTag("v3.11.3-beb2108"))
        assertFalse(ArbNitroDockerTags.isCanonicalFullNodeTag("v3.11.3-beb2108-slim"))
        assertFalse(ArbNitroDockerTags.isCanonicalFullNodeTag("v3.11.3-beb2108-arm64"))
        assertNull(ArbNitroDockerTags.fromReleaseBody(""))
    }
}
