package rpcnode.toolkit.clients.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientArtifactRole
import rpcnode.toolkit.clients.domain.model.ClientArtifactSpec
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.model.ClientRelease
import rpcnode.toolkit.clients.domain.model.ClientVersionSource

class ClientDowngradeGuardTest
{
    @Test
    fun older_build_is_detected_across_the_odd_tron_version_formats()
    {
        assertTrue(isOlderClientVersion("4.8.2.1", "4.8.2.3"))
        assertTrue(isOlderClientVersion("4.8.2.1", "nile-v4.8.2.3-pq1-build1"))
        assertTrue(isOlderClientVersion("4.8.2.1-PQ1-build1", "nile-v4.8.2.3-pq1-build1"))
        assertTrue(isOlderClientVersion("4.8", "4.8.1"))
        assertFalse(isOlderClientVersion("4.8.2.3", "4.8.2.3"))
        assertFalse(isOlderClientVersion("4.8.2.4", "4.8.2.3"))
        assertFalse(isOlderClientVersion("v10.0.0", "9.9.9"))
        assertFalse(isOlderClientVersion("", "4.8.2.3"))
        assertFalse(isOlderClientVersion("latest", "4.8.2.3"))
    }

    private val tronResolver = mapOf(
        NetworkId.TRON to ClientReleaseResolver {
            // the live GitHub feed answered nothing (rate limit / connect timeout)
            null
        },
    )

    private fun tronMainnet() = ClientProgramSpec(
        network = NetworkId.TRON,
        env = EnvId.MAINNET,
        programId = "FullNode.jar",
        source = ClientVersionSource.Pinned(version = "4.8.2.1", tag = "GreatVoyage-v4.8.2.1", label = "tronprotocol/java-tron"),
        artifacts = listOf(
            ClientArtifactSpec(name = "FullNode.jar", role = ClientArtifactRole.ARTIFACT, urlTemplate = "https://example/{tag}"),
        ),
    )

    private val feedDown = GitHubReleaseClient { _, _ -> null }

    @Test
    fun feed_down_and_catalog_pin_older_than_installed_is_not_offered_as_an_update() = runTest {
        val resolved = resolveClientRelease(tronMainnet(), feedDown, tronResolver, installedVersion = "4.8.2.3")
        assertEquals("", resolved.version)
        assertNotNull(resolved.error)
        assertTrue(resolved.error!!.contains("older than the installed 4.8.2.3"), resolved.error)
    }

    @Test
    fun a_newer_catalog_pin_still_works_as_the_offline_fallback() = runTest {
        val resolved = resolveClientRelease(tronMainnet(), feedDown, tronResolver, installedVersion = "4.8.1.0")
        assertEquals("4.8.2.1", resolved.version)
        assertNull(resolved.error)
    }

    @Test
    fun nothing_installed_yet_uses_the_catalog_pin() = runTest {
        val resolved = resolveClientRelease(tronMainnet(), feedDown, tronResolver)
        assertEquals("4.8.2.1", resolved.version)
        assertNull(resolved.error)
    }

    @Test
    fun the_live_feed_wins_when_it_answers_even_if_newer_than_the_pin() = runTest {
        val live = mapOf(
            NetworkId.TRON to ClientReleaseResolver {
                ClientRelease(version = "4.8.2.3", tag = "GreatVoyage-v4.8.2.3", sourceLabel = "tronprotocol/java-tron")
            },
        )
        val resolved = resolveClientRelease(tronMainnet(), feedDown, live, installedVersion = "4.8.2.3")
        assertEquals("4.8.2.3", resolved.version)
        assertNull(resolved.error)
    }

    private fun bscMainnet() = tronMainnet().copy(
        network = NetworkId.BSC,
        programId = "geth",
        source = ClientVersionSource.Pinned(version = "1.7.8", tag = "v1.7.8", label = "bnb-chain/bsc"),
    )

    private fun liveBsc(version: String) = mapOf(
        NetworkId.BSC to ClientReleaseResolver {
            ClientRelease(version = version, tag = "v$version", sourceLabel = "bnb-chain/bsc")
        },
    )

    @Test
    fun a_network_release_older_than_installed_keeps_the_installed_build() = runTest {
        // bsc/mainnet: installed 1.8.0-alpha, newest release carrying the config zip is 1.7.8
        val resolved = resolveClientRelease(
            bscMainnet(), feedDown, liveBsc("1.7.8"),
            installedVersion = "1.8.0-alpha", installedTag = "v1.8.0-alpha",
        )
        assertEquals("1.8.0-alpha", resolved.version)
        assertEquals("v1.8.0-alpha", resolved.tag)
        assertNull(resolved.error)
    }

    @Test
    fun a_network_release_newer_than_installed_is_still_offered() = runTest {
        val resolved = resolveClientRelease(
            bscMainnet(), feedDown, liveBsc("1.8.0"),
            installedVersion = "1.8.0-alpha2", installedTag = "v1.8.0-alpha2",
        )
        assertEquals("1.8.0", resolved.version)
        assertEquals("v1.8.0", resolved.tag)
    }

    @Test
    fun a_release_candidate_does_not_replace_the_same_release() = runTest {
        val resolved = resolveClientRelease(
            bscMainnet(), feedDown, liveBsc("1.8.0-rc1"),
            installedVersion = "1.8.0", installedTag = "v1.8.0",
        )
        assertEquals("1.8.0", resolved.version)
    }

    @Test
    fun prereleases_order_alpha_beta_rc_then_release()
    {
        assertTrue(isOlderClientVersion("1.8.0-alpha", "1.8.0-alpha2"))
        assertTrue(isOlderClientVersion("1.8.0-alpha2", "1.8.0-beta"))
        assertTrue(isOlderClientVersion("1.8.0-beta3", "1.8.0-rc1"))
        assertTrue(isOlderClientVersion("1.8.0-rc1", "1.8.0"))
        assertFalse(isOlderClientVersion("1.8.0", "1.8.0-rc1"))
        assertFalse(isOlderClientVersion("1.8.0", "1.8.0-alpha2"))
        assertTrue(isOlderClientVersion("1.7.8", "1.8.0-alpha"))
        assertFalse(isOlderClientVersion("1.9.0", "1.8.0-alpha"))
    }
}
