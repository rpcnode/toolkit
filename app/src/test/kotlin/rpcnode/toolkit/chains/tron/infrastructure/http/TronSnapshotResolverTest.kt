package rpcnode.toolkit.chains.tron.infrastructure.http

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.application.snapshot.SnapshotSizeProbe
import rpcnode.toolkit.networks.domain.model.SnapshotMirrorSpec
import rpcnode.toolkit.networks.domain.repository.SnapshotMirrorCatalog
import rpcnode.toolkit.networks.infrastructure.http.DirectoryListingResolver
import rpcnode.toolkit.networks.infrastructure.http.SnapshotPresenceProbe

private class FakeMirrors(private val specs: List<SnapshotMirrorSpec>) : SnapshotMirrorCatalog
{
    override fun mirror(network: NetworkId, env: EnvId, typeId: String): SnapshotMirrorSpec? =
        specs.firstOrNull { it.network == network && it.env == env && it.typeId == typeId }

    override fun typesFor(network: NetworkId, env: EnvId): List<SnapshotMirrorSpec> =
        specs.filter { it.network == network && it.env == env }
}

private class RecordingListingResolver(private val response: String? = "https://mirror.example/latest.tgz") : DirectoryListingResolver
{
    val calls = mutableListOf<Pair<String, String>>()

    override suspend fun latestArchiveUrl(mirrorRootUrl: String, entryPattern: Regex, archiveName: String): String?
    {
        calls += mirrorRootUrl to archiveName
        return response
    }
}

private class RecordingSizeProbe(private val bytes: Long? = 1_024L) : SnapshotSizeProbe
{
    val urls = mutableListOf<String>()

    override suspend fun bytes(url: String): Long?
    {
        urls += url
        return bytes
    }
}

private class RecordingPresenceProbe(private val present: Set<String> = emptySet()) : SnapshotPresenceProbe
{
    val urls = mutableListOf<String>()

    override suspend fun present(url: String): Boolean
    {
        urls += url
        return url in present
    }
}

class TronSnapshotResolverTest
{
    private val fixedClock = Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC)

    private val catalog = FakeMirrors(
        listOf(
            SnapshotMirrorSpec(
                network = NetworkId.TRON,
                env = EnvId.MAINNET,
                typeId = "full",
                mirror = "http://34.86.86.229/",
                filename = "FullNode_output-directory.tgz",
                discover = "listing",
            ),
            SnapshotMirrorSpec(
                network = NetworkId.TRON,
                env = EnvId.NILE,
                typeId = "full",
                mirror = "https://snapshots.nileex.io/",
                filename = "FullNode_output-directory.tgz",
                discover = "dated",
            ),
            SnapshotMirrorSpec(
                network = NetworkId.TRON,
                env = EnvId.NILE,
                typeId = "lite",
                mirror = "https://snapshots.nileex.io/",
                filename = "LiteFullNode_output-directory.tgz",
                discover = "dated",
            ),
        ),
    )

    @Test
    fun shasta_has_no_mirror_and_is_not_scraped() = runTest {
        val listing = RecordingListingResolver()
        val size = RecordingSizeProbe()
        val presence = RecordingPresenceProbe()
        val resolver = TronSnapshotResolver(catalog, listing, size, presence, fixedClock)
        assertNull(resolver.resolve(EnvId.SHASTA, "full"))
        assertEquals(0, listing.calls.size)
        assertEquals(0, presence.urls.size)
    }

    @Test
    fun mainnet_full_scrapes_official_mirror() = runTest {
        val listing = RecordingListingResolver()
        val size = RecordingSizeProbe(2_900L * 1_024 * 1_024 * 1_024)
        val resolver = TronSnapshotResolver(catalog, listing, size, RecordingPresenceProbe(), fixedClock)
        val archive = resolver.resolve(EnvId.MAINNET, "full")
        assertEquals("https://mirror.example/latest.tgz", archive?.url)
        assertTrue(archive!!.streamUnpack)
        assertEquals(listOf("http://34.86.86.229/" to "FullNode_output-directory.tgz"), listing.calls)
    }

    @Test
    fun nile_lite_probes_dated_lite_archive() = runTest {
        val hit = "https://snapshots.nileex.io/backup20260831/LiteFullNode_output-directory.tgz"
        val presence = RecordingPresenceProbe(present = setOf(hit))
        val size = RecordingSizeProbe(42L)
        val resolver = TronSnapshotResolver(catalog, RecordingListingResolver(), size, presence, fixedClock)
        val archive = resolver.resolve(EnvId.NILE, "lite")
        assertEquals(hit, archive?.url)
        assertEquals(42L, archive?.sizeBytes)
        assertEquals(
            listOf(
                "https://snapshots.nileex.io/backup20260901/LiteFullNode_output-directory.tgz",
                hit,
            ),
            presence.urls,
        )
    }
}
