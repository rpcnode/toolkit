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
}
