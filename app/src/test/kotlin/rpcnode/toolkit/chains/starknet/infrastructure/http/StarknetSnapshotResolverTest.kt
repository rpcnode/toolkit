package rpcnode.toolkit.chains.starknet.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.infrastructure.catalog.YamlSnapshotMirrorCatalog

class StarknetSnapshotResolverTest
{
    private val sizes = mutableMapOf<String, Long>()
    private val resolver = StarknetSnapshotResolver(YamlSnapshotMirrorCatalog()) { url -> sizes[url] }

    @Test
    fun resolves_every_env_and_flavor_to_the_official_latest_url_as_a_stream() = runTest {
        sizes["https://juno-snapshots.nethermind.io/files/mainnet-pruned/latest"] = 101_844_116_061L
        val pruned = assertNotNull(resolver.resolve(EnvId.MAINNET, "pruned"))
        assertEquals("https://juno-snapshots.nethermind.io/files/mainnet-pruned/latest", pruned.url)
        assertTrue(pruned.streamUnpack)
        assertEquals(101_844_116_061L, pruned.sizeBytes)

        assertEquals("https://juno-snapshots.nethermind.io/files/mainnet/latest", resolver.resolve(EnvId.MAINNET, "full")?.url)
        assertEquals("https://juno-snapshots.nethermind.io/files/sepolia/latest", resolver.resolve(EnvId.SEPOLIA, "full")?.url)
        assertEquals("https://juno-snapshots.nethermind.io/files/sepolia-pruned/latest", resolver.resolve(EnvId.SEPOLIA, "pruned")?.url)
        // an unknown / blank type is the default (pruned); a size that cannot be read does not hide the snapshot
        assertEquals("https://juno-snapshots.nethermind.io/files/sepolia-pruned/latest", resolver.resolve(EnvId.SEPOLIA, "")?.url)
        assertNull(resolver.resolve(EnvId.SEPOLIA, "")?.sizeBytes)
        assertNull(resolver.resolve(EnvId.TESTNET, "pruned"), "no such env")
    }

    @Test
    fun official_url_detection_selects_the_zstd_stream_only_for_the_juno_mirror()
    {
        assertTrue(StarknetSnapshotResolver.isOfficialUrl("https://juno-snapshots.nethermind.io/files/mainnet/latest"))
        assertTrue(StarknetSnapshotResolver.isOfficialUrl("https://JUNO-snapshots.nethermind.io/files/sepolia-pruned/latest/"))
        assertFalse(StarknetSnapshotResolver.isOfficialUrl("https://example.com/files/mainnet/latest"))
        assertFalse(StarknetSnapshotResolver.isOfficialUrl("https://juno-snapshots.nethermind.io/files/mainnet/juno_mainnet.tar.zst"))
        assertFalse(StarknetSnapshotResolver.isOfficialUrl("not a url"))
        assertFalse(StarknetSnapshotResolver.isOfficialUrl(""))
    }

    @Test
    fun the_mirror_catalog_lists_both_flavors_for_both_envs()
    {
        val mirrors = YamlSnapshotMirrorCatalog()
        for (env in listOf(EnvId.MAINNET, EnvId.SEPOLIA))
        {
            assertEquals(listOf("full", "pruned"), mirrors.typesFor(NetworkId.STARKNET, env).map { it.typeId })
        }
    }
}
