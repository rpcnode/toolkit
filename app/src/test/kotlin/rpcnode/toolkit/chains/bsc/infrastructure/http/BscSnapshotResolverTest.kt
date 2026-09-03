package rpcnode.toolkit.chains.bsc.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumEthRpc

class BscSnapshotResolverTest
{
    @Test
    fun resolve_returns_official_sentinel() = runTest {
        val archive = BscSnapshotResolver().resolve(EnvId.MAINNET, "pruned")
        assertEquals("bsc-official://mainnet/pruned", archive?.url)
        assertEquals(false, archive?.streamUnpack)
    }

    @Test
    fun parse_and_with_snap_dir()
    {
        val ref = BscSnapshotResolver.parse("bsc-official://testnet/full?snap=/data/bsc/testnet/snapshots")
        assertEquals("testnet", ref?.env)
        assertEquals("full", ref?.flavor)
        assertEquals("/data/bsc/testnet/snapshots", ref?.snapDir)
        assertTrue(BscSnapshotResolver.isOfficialUrl("bsc-official://mainnet/pruned"))
        assertNull(BscSnapshotResolver.parse("https://example.com/x.tar"))
        val with = BscSnapshotResolver.withSnapDir("bsc-official://mainnet/pruned", "/data/snap")
        assertTrue(with.contains("snap="))
    }

    @Test
    fun parse_decodes_url_encoded_absolute_snap_dir()
    {
        val encoded = BscSnapshotResolver.withSnapDir(
            "bsc-official://testnet/pruned",
            "/mnt/raid0/rpcnode/bsc/testnet/snapshots",
        )
        assertTrue(encoded.contains("%2F"), "encoder must escape slashes: $encoded")
        val ref = BscSnapshotResolver.parse(encoded)
        assertEquals("/mnt/raid0/rpcnode/bsc/testnet/snapshots", ref?.snapDir)
        assertTrue(ref?.snapDir!!.startsWith("/"), "snap dir must stay absolute after decode")
    }
}

class BscEthRpcReuseTest
{
    @Test
    fun eth_block_number_hex_parses()
    {
        assertEquals(56L, EthereumEthRpc.parseHexInt64("0x38"))
        assertEquals(
            0x1234L,
            EthereumEthRpc.parseBlockNumber("""{"jsonrpc":"2.0","id":1,"result":"0x1234"}"""),
        )
    }
}
