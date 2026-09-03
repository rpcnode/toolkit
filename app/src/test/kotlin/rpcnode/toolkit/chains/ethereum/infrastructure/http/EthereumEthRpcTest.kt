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
}
