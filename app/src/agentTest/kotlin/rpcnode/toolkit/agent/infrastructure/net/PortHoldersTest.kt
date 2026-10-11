package rpcnode.toolkit.agent.infrastructure.net

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortHoldersTest
{
    private val table = """
        State  Recv-Q Send-Q Local Address:Port Peer Address:Port Process
        LISTEN 0      4096         0.0.0.0:48990      0.0.0.0:*    users:(("java",pid=4072689,fd=5))
        LISTEN 0      128        127.0.0.1:18332      0.0.0.0:*    users:(("bitcoind",pid=1234,fd=9))
        LISTEN 0      128            [::1]:18332         [::]:*    users:(("bitcoind",pid=1234,fd=10))
        LISTEN 0      4096               *:18888            *:*    users:(("java",pid=77,fd=3),("x",pid=78,fd=3))
        LISTEN 0      128        127.0.0.1:53           0.0.0.0:*
    """.trimIndent()

    @AfterTest
    fun reset() = PortHolders.reset()

    @Test
    fun the_whole_listener_table_is_read_in_one_go()
    {
        val parsed = PortHolders.parse(table)
        assertEquals("java (pid 4072689)", parsed[48990])
        assertEquals("bitcoind (pid 1234)", parsed[18332])
        assertEquals("java (pid 77)", parsed[18888])
        assertNull(parsed[53], "a listener without a process name is just not listed")
    }

    @Test
    fun ten_busy_ports_cost_one_slow_ss_call_not_ten()
    {
        var calls = 0
        var now = 1_000L
        repeat(10)
        {
            PortHolders.holder(48990 + it, nowMs = { now }, run = { calls++; table })
        }
        assertEquals(1, calls)
        assertEquals("java (pid 4072689)", PortHolders.holder(48990, nowMs = { now }, run = { calls++; table }))
        // after the cache expires the table is read again
        now += 20_000L
        PortHolders.holder(48990, nowMs = { now }, run = { calls++; table })
        assertEquals(2, calls)
    }

    @Test
    fun a_slow_or_failing_ss_gives_no_name_but_never_an_error()
    {
        assertNull(PortHolders.holder(48990, nowMs = { 5L }, run = { null }))
    }
}
