package rpcnode.toolkit.chains.polygon.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolygonHeimdallStatusTest
{
    @Test
    fun `parses a catching up status`()
    {
        val body = """{"jsonrpc":"2.0","id":-1,"result":{"node_info":{},"sync_info":{
            "latest_block_hash":"8ABA","latest_block_height":"8788501",
            "latest_block_time":"2025-06-24T20:00:00.123456789Z","catching_up":true}}}"""
        val s = assertNotNull(PolygonHeimdallStatus.parse(body))
        assertTrue(s.catchingUp)
        assertEquals(8788501L, s.latestBlockHeight)
        assertEquals(1750795200L, s.latestBlockTime)
    }

    @Test
    fun `garbage or a missing sync_info is unknown`()
    {
        assertNull(PolygonHeimdallStatus.parse(null))
        assertNull(PolygonHeimdallStatus.parse("not json"))
        assertNull(PolygonHeimdallStatus.parse("""{"result":{}}"""))
    }
}
