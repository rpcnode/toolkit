package rpcnode.toolkit.chains.stellar.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StellarHistoryTest
{
    @Test
    fun parses_the_three_windows_and_defaults_to_a_week()
    {
        assertEquals(StellarHistoryPolicy("week", 120_960, true), StellarHistory.parse(null))
        assertEquals(StellarHistoryPolicy("month", 518_400, true), StellarHistory.parse("Month"))
        assertEquals(StellarHistoryPolicy("forward", 4_294_967_295L, false), StellarHistory.parse("forward"))
        assertEquals("week", StellarHistory.parse("garbage").mode)
        assertEquals("forward", StellarHistory.fromJson("""{"history":"forward"}""").mode)
        assertEquals("week", StellarHistory.fromJson("not json").mode)
    }

    @Test
    fun never_prune_never_backfills()
    {
        assertFalse(StellarHistory.parse("forward").backfillWanted)
    }
}
