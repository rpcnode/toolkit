package rpcnode.toolkit.chains.nonevm.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import rpcnode.toolkit.chains.near.infrastructure.http.NearStatus
import rpcnode.toolkit.chains.stacks.infrastructure.http.StacksInfo
import rpcnode.toolkit.chains.substrate.infrastructure.http.SubstrateRpc
import rpcnode.toolkit.chains.aleo.infrastructure.http.AleoHeight
import rpcnode.toolkit.chains.fuel.infrastructure.http.FuelGraphql
import rpcnode.toolkit.chains.substrate.infrastructure.http.SubstrateNodeHeightProbe

/** The JSON of the public RPCs / nodes the readers were verified against (2026-10-13/14). */
class HeightReaderTest
{
    @Test
    fun substrate_header_is_a_hex_number()
    {
        val body = """{"jsonrpc":"2.0","id":1,"result":{"parentHash":"0xab","number":"0x1fdeb31","stateRoot":"0x01","extrinsicsRoot":"0x02","digest":{"logs":[]}}}"""
        assertEquals(33_418_033L, SubstrateRpc.HEADER.parse(body))
        assertNull(SubstrateRpc.HEADER.parse("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601}}"""))
        assertNull(SubstrateRpc.HEADER.parse(null))
    }

    @Test
    fun substrate_health_drives_the_syncing_flag()
    {
        val h = SubstrateRpc.parseHealth("""{"jsonrpc":"2.0","id":1,"result":{"peers":23,"isSyncing":false,"shouldHavePeers":true}}""")!!
        assertEquals(23, h.peers)
        assertEquals(false, SubstrateNodeHeightProbe.toReading(100, h).syncing)
        // warp sync: the best head stays at 0 whatever health says
        assertEquals(true, SubstrateNodeHeightProbe.toReading(0, h).syncing)
        assertEquals(true, SubstrateNodeHeightProbe.toReading(100, h.copy(isSyncing = true)).syncing)
        assertNull(SubstrateRpc.parseHealth("{}"))
    }

    @Test
    fun near_status()
    {
        val body = """{"jsonrpc":"2.0","result":{"chain_id":"mainnet","sync_info":{"latest_block_height":191234567,"latest_block_time":"2026-10-13T20:37:43Z","syncing":false}},"id":"x"}"""
        assertEquals(191_234_567L, NearStatus.HEIGHT.parse(body))
        val r = NearStatus.parse(body)!!
        assertEquals(false, r.syncing)
        assertEquals(true, NearStatus.parse(body.replace("false", "true"))!!.syncing)
    }

    @Test
    fun stacks_info()
    {
        val body = """{"peer_version":4194304,"pox_consensus":"ab","burn_block_height":905321,"stable_pox_consensus":"cd","stable_burn_block_height":905320,"stacks_tip_height":3912345}"""
        assertEquals(3_912_345L, StacksInfo.HEIGHT.parse(body))
        assertEquals("/v2/info", StacksInfo.HEIGHT.localPath)
    }

    @Test
    fun aleo_plain_body()
    {
        assertEquals(22_782_109L, AleoHeight.READER.parse("22782109"))
        assertEquals(22_782_109L, AleoHeight.READER.parse("22782109\n"))
        assertNull(AleoHeight.READER.parse("not found"))
    }

    @Test
    fun fuel_graphql_string_height()
    {
        val body = """{"data":{"chain":{"latestBlock":{"height":"66712345"}}}}"""
        assertEquals(66_712_345L, FuelGraphql.HEIGHT.parse(body))
        assertNull(FuelGraphql.HEIGHT.parse("""{"errors":[{"message":"x"}]}"""))
    }
}
