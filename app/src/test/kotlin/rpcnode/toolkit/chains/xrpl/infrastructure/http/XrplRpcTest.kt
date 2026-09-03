package rpcnode.toolkit.chains.xrpl.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rpcnode.toolkit.chains.xrpl.infrastructure.XrplHistory
import rpcnode.toolkit.chains.xrpl.infrastructure.XrplUnitBodies
import rpcnode.toolkit.chains.xrpl.infrastructure.XrplValidators

class XrplRpcTest
{
    @Test
    fun parse_server_info_reads_seq_and_complete()
    {
        val raw = """
            {"result":{"status":"success","info":{
              "server_state":"full",
              "complete_ledgers":"106700000-106718784",
              "peers":42,
              "validated_ledger":{"seq":106718784}
            }}}
        """.trimIndent()
        val info = XrplRpc.parseServerInfo(raw)!!
        assertTrue(info.ok)
        assertEquals("full", info.state)
        assertEquals(106_718_784L, info.seq)
        assertEquals("106700000-106718784", info.complete)
        assertEquals(42, info.peers)
    }

    @Test
    fun parse_complete_ledgers_and_history_window()
    {
        val (lo, hi) = XrplHistory.parseCompleteLedgers("100-200,250-300")
        assertEquals(100L, lo)
        assertEquals(300L, hi)
        assertEquals(0L to 0L, XrplHistory.parseCompleteLedgers("empty"))
        assertTrue(XrplHistory.tipLive("full"))
        assertFalse(XrplHistory.tipLive("connected"))
        val weeks = XrplHistory.parse("weeks")
        assertEquals(300_000, weeks.ledgers)
        assertTrue(XrplHistory.historyOk("mainnet", 100_000, 400_000, 400_010, weeks))
    }

    @Test
    fun cfg_and_validators_are_stock()
    {
        val cluster = rpcnode.toolkit.chains.xrpl.infrastructure.XrplClusters.lookup("mainnet")
        val ports = rpcnode.toolkit.chains.xrpl.infrastructure.XrplPortTable.forEnv("mainnet")
        val cfg = XrplUnitBodies.cfg(
            cluster = cluster,
            etc = java.nio.file.Path.of("/data/rpcnode/xrpl/mainnet/ledger"),
            data = java.nio.file.Path.of("/data/rpcnode/xrpl/mainnet/ledger"),
            ports = ports,
            policy = XrplHistory.parse("weeks"),
            hasLedger = true,
        )
        assertTrue(cfg.contains("[ledger_history]"))
        assertTrue(cfg.contains("300000"))
        assertTrue(cfg.contains("online_delete=300000"))
        assertTrue(cfg.contains("[peers_max]"))
        assertTrue(cfg.contains("100"))
        assertTrue(cfg.contains("port = 5005"))
        assertTrue(cfg.contains("s2.ripple.com 51235"))
        val vl = XrplValidators.body("mainnet")
        assertTrue(vl.contains("vl.ripple.com"))
        assertFalse(vl.contains("validator_list_threshold"))
    }

    @Test
    fun broken_32_versions_detected()
    {
        assertTrue(XrplClientReleaseResolver.isBroken32("3.2.0"))
        assertTrue(XrplClientReleaseResolver.isBroken32("rippled-3.2.1"))
        assertFalse(XrplClientReleaseResolver.isBroken32("3.3.0"))
    }
}
