package rpcnode.toolkit.chains.ton.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TonValidatorLagTest
{
    @Test
    fun newest_status_line_wins()
    {
        val log = """
            [ 1][t31][2026-10-11 16:09:50][manager.cpp:2759][!manager]	STATUS: last_masterchain_block_ago=14.6h last_known_key_block_ago=14.7h shard_client_ago=14.6h
            [ 1][t31][2026-10-11 17:45:50][manager.cpp:2759][!manager]	STATUS: last_masterchain_block_ago=14.0h last_known_key_block_ago=14.3h shard_client_ago=14.1h
        """.trimIndent()
        assertEquals(14.0 * 3600, TonValidatorLag.lastLagSeconds(log)!!, 1e-6)
    }

    @Test
    fun units()
    {
        assertEquals(35.5 * 60, TonValidatorLag.lastLagSeconds("last_masterchain_block_ago=35.5m")!!, 1e-6)
        assertEquals(12.0, TonValidatorLag.lastLagSeconds("last_masterchain_block_ago=12s")!!, 1e-6)
        assertEquals(86400.0 * 2, TonValidatorLag.lastLagSeconds("last_masterchain_block_ago=2d")!!, 1e-6)
        assertNull(TonValidatorLag.lastLagSeconds("no status here"))
    }

    @Test
    fun catch_up_share()
    {
        assertEquals(0.0, TonValidatorLag.catchUpPct(14.0 * 3600, 14.0 * 3600))
        assertEquals(50.0, TonValidatorLag.catchUpPct(10.0, 5.0))
        assertEquals(99.0, TonValidatorLag.catchUpPct(10.0, 0.0))
        assertEquals(0.0, TonValidatorLag.catchUpPct(0.0, 0.0))
    }
}
