package rpcnode.toolkit.chains.ethereum.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EthereumEthRpcTest
{
    @Test
    fun parse_block_number_hex()
    {
        val body = """{"jsonrpc":"2.0","id":1,"result":"0x10"}"""
        assertEquals(16L, EthereumEthRpc.parseBlockNumber(body))
    }

    @Test
    fun parse_block_number_null_on_error()
    {
        assertNull(EthereumEthRpc.parseBlockNumber("""{"jsonrpc":"2.0","id":1,"error":{"message":"x"}}"""))
        assertNull(EthereumEthRpc.parseBlockNumber(null))
        assertNull(EthereumEthRpc.parseBlockNumber(""))
    }

    @Test
    fun parse_block_timestamp_hex()
    {
        val body = """{"jsonrpc":"2.0","id":1,"result":{"number":"0x1","timestamp":"0x64"}}"""
        assertEquals(100L, EthereumEthRpc.parseBlockTimestamp(body))
    }

    @Test
    fun parse_block_timestamp_null_when_missing()
    {
        assertNull(EthereumEthRpc.parseBlockTimestamp("""{"jsonrpc":"2.0","id":1,"result":null}"""))
        assertNull(EthereumEthRpc.parseBlockTimestamp("""{"jsonrpc":"2.0","id":1,"error":{"message":"x"}}"""))
        assertNull(EthereumEthRpc.parseBlockTimestamp(null))
    }

    @Test
    fun parse_syncing_false()
    {
        val body = """{"jsonrpc":"2.0","id":1,"result":false}"""
        val st = EthereumEthRpc.parseSyncing(body)!!
        assertEquals(false, st.syncing)
        assertEquals(100.0, st.blockPct)
    }

    @Test
    fun parse_syncing_object()
    {
        val body =
            """{"jsonrpc":"2.0","id":1,"result":{"currentBlock":"0xa","highestBlock":"0x64"}}"""
        val st = EthereumEthRpc.parseSyncing(body)!!
        assertEquals(true, st.syncing)
        assertEquals(10L, st.currentBlock)
        assertEquals(100L, st.highestBlock)
        assertEquals(10.0, st.blockPct)
    }

    @Test
    fun parse_snap_sync_pct_min_of_state_and_chain()
    {
        val log =
            """
            INFO Syncing: state download in progress      synced=17.52% state=68.66GiB
            INFO Syncing: chain download in progress      synced=33.56% chain=32.69GiB
            INFO Syncing: state download in progress      synced=18.00% state=70.42GiB
            INFO Syncing: chain download in progress      synced=33.70% chain=34.07GiB
            """.trimIndent()
        assertEquals(18.0, EthereumEthRpc.parseSnapSyncPctFromLog(log))
    }

    @Test
    fun parse_snap_sync_pct_null_when_absent()
    {
        assertNull(EthereumEthRpc.parseSnapSyncPctFromLog("Forkchoice requested sync to new head"))
        assertNull(EthereumEthRpc.parseSnapSyncPctFromLog(null))
    }

    @Test
    fun header_sync_phase_gives_progress_from_downloaded_and_left()
    {
        val log = """
            INFO [10-09|09:38:34.321] Looking for peers                        peercount=2 tried=22 static=0
            INFO [10-09|09:38:36.893] Syncing beacon headers                   downloaded=8,214,528 left=3,667,778  eta=36m18.802s
            INFO [10-09|09:38:45.935] Syncing beacon headers                   downloaded=8,220,672 left=3,661,634  eta=36m17.554s
        """.trimIndent()
        val pct = EthereumEthRpc.parseSnapSyncPctFromLog(log)!!
        assertEquals(69.2, Math.round(pct * 10) / 10.0)
    }

    @Test
    fun state_and_chain_download_lines_take_over_from_the_header_phase()
    {
        val log = """
            INFO Syncing beacon headers                   downloaded=11,000,000 left=1,000
            INFO Syncing: state download in progress      synced=4.00% state=1GiB
            INFO Syncing: chain download in progress      synced=9.00% chain=1GiB
        """.trimIndent()
        assertEquals(4.0, EthereumEthRpc.parseSnapSyncPctFromLog(log))
    }
}
