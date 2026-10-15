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

class BscSnapshotStreamModeTest
{
    @Test
    fun stream_mode_round_trips_with_snap_dir()
    {
        val url = BscSnapshotResolver.withOptions("bsc-official://mainnet/pruned", "/mnt/snap", stream = true)
        val ref = BscSnapshotResolver.parse(url)
        assertEquals(true, ref?.stream)
        assertEquals("/mnt/snap", ref?.snapDir)
        assertEquals("pruned", ref?.flavor)
    }

    @Test
    fun official_mode_is_default_and_survives_with_snap_dir()
    {
        assertEquals(false, BscSnapshotResolver.parse("bsc-official://mainnet/pruned?snap=%2Fx")?.stream)
        val streamed = BscSnapshotResolver.withOptions("bsc-official://testnet/full", null, stream = true)
        assertEquals("bsc-official://testnet/full?mode=stream", streamed)
        val moved = BscSnapshotResolver.withSnapDir(streamed, "/data/snap")
        assertEquals(true, BscSnapshotResolver.parse(moved)?.stream)
        assertEquals("/data/snap", BscSnapshotResolver.parse(moved)?.snapDir)
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
