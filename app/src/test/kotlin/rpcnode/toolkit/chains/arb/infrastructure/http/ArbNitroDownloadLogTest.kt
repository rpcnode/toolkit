package rpcnode.toolkit.chains.arb.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ArbNitroDownloadLogTest
{
    @Test
    fun parses_transfer_line()
    {
        val ln = "transferred 5368709120 / 536870912000 bytes (1.00%) [458.86Mbps, 2h34m26s remaining]"
        val got = assertNotNull(ArbNitroDownloadLog.parse(ln))
        assertEquals(1.0, got.pct)
        assertEquals(5_368_709_120L, got.doneBytes)
        assertEquals(536_870_912_000L, got.totalBytes)
        assertEquals("458.86Mbps", got.rate)
        assertEquals("2h34m26s", got.eta)
    }

    @Test
    fun keeps_last_carriage_progress_and_strips_ansi()
    {
        val text =
            "INFO downloading\r" +
                "\u001b[2K  transferred 1000 / 10000 bytes (10.00%) [100Mbps, 1h remaining]\r" +
                "\u001b[2K  transferred 1889981056 / 536870912000 bytes (0.35%) [56.87Mbps, 20h54m16s remaining]\r" +
                "\u001b[2K  transferred 1905856576 / 536870912000 bytes (0.35%) [65.13Mbps, 18h15m10s remaining]\n" +
                "INFO done part\n"
        val got = assertNotNull(ArbNitroDownloadLog.parse(text))
        assertEquals(0.35, got.pct)
        assertEquals(1_905_856_576L, got.doneBytes)
        assertEquals("65.13Mbps", got.rate)
        assertEquals("18h15m10s", got.eta)
    }

    @Test
    fun empty_when_no_match()
    {
        assertNull(ArbNitroDownloadLog.parse("INFO connected to l1 chain"))
    }
}
