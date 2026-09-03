package rpcnode.toolkit.chains.base.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.base.infrastructure.BaseClusters
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumEthRpc

class BaseSnapshotResolverTest
{
    @Test
    fun resolves_public_manifest_url() = runBlocking {
        val archive = BaseSnapshotResolver().resolve(EnvId.MAINNET, "archive")
        assertEquals(
            "https://chain.base.org/api/snapshots?env=mainnet&flavor=archive",
            archive?.url,
        )
        assertEquals(false, archive?.streamUnpack)
    }

    @Test
    fun parse_query_and_legacy_sentinel()
    {
        val fromApi = BaseSnapshotResolver.parse(
            "https://chain.base.org/api/snapshots?env=sepolia&flavor=minimal",
        )
        assertEquals("sepolia", fromApi?.env)
        assertEquals("minimal", fromApi?.flavor)

        val legacy = BaseSnapshotResolver.parse("base-official://sepolia/full")
        assertEquals("sepolia", legacy?.env)
        assertEquals("full", legacy?.flavor)

        assertTrue(BaseSnapshotResolver.isOfficialUrl(BaseClusters.SNAPSHOT_API_URL))
        assertTrue(BaseSnapshotResolver.isOfficialUrl("base-official://mainnet/archive"))
        assertNull(BaseSnapshotResolver.parse("https://example.com/snap.tgz"))
    }

    @Test
    fun parse_cdn_manifest_url()
    {
        val url =
            "http://cdn.example:8095/snapshots/base/sepolia/archive/1788307203/manifest.json?env=sepolia&flavor=full"
        assertTrue(BaseSnapshotResolver.isCdnManifestUrl(url))
        assertTrue(BaseSnapshotResolver.isBaseDownloadUrl(url))
        val ref = BaseSnapshotResolver.parse(url)
        assertEquals("sepolia", ref?.env)
        assertEquals("full", ref?.flavor)
        assertEquals(
            "http://cdn.example:8095/snapshots/base/sepolia/archive/1788307203/manifest.json",
            BaseSnapshotResolver.manifestUrlForDownload(url),
        )
        assertNull(
            BaseSnapshotResolver.manifestUrlForDownload(
                "https://chain.base.org/api/snapshots?env=mainnet&flavor=archive",
            ),
        )
    }
}

class BaseEthRpcParseTest
{
    @Test
    fun parses_hex_block_number()
    {
        assertEquals(42L, EthereumEthRpc.parseBlockNumber("""{"jsonrpc":"2.0","id":1,"result":"0x2a"}"""))
        assertNull(EthereumEthRpc.parseBlockNumber("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000}}"""))
    }
}
