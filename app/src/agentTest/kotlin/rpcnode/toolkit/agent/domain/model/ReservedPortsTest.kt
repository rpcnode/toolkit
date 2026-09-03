package rpcnode.toolkit.agent.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rpcnode.toolkit.agent.domain.model.AGENT_API_PORT
import rpcnode.toolkit.agent.domain.model.AGENT_RESERVED_PORTS

class ReservedPortsTest
{
    @Test
    fun parse_port_range()
    {
        assertEquals(EphemeralPortRange(32768, 60999), parsePortRange("32768\t60999"))
        assertEquals(EphemeralPortRange(1024, 65535), parsePortRange(" 1024 65535\n"))
        assertNull(parsePortRange("32768"))
        assertNull(parsePortRange("lo hi"))
        assertNull(parsePortRange("60999 32768"))
        assertNull(parsePortRange(""))
    }

    @Test
    fun parse_port_list()
    {
        assertEquals(emptyList(), parsePortList(""))
        assertEquals(listOf(39491), parsePortList("39491"))
        assertEquals(listOf(39490, 39491, 39492, 39493), parsePortList("39490-39493"))
        assertEquals(listOf(8080, 39490, 39491, 40090), parsePortList("40090,39490-39491,8080"))
        assertEquals(listOf(39490), parsePortList("abc,,39490,-5"))
    }

    @Test
    fun format_port_list()
    {
        assertEquals("", formatPortList(emptyList()))
        assertEquals("39491", formatPortList(listOf(39491)))
        assertEquals("39490-39493", formatPortList(listOf(39490, 39491, 39492, 39493)))
        assertEquals("8080,39490-39491,40090", formatPortList(listOf(40090, 39490, 39491, 40090, 8080)))
        assertEquals("100-101", formatPortList(listOf(100, 101)))
    }

    @Test
    fun format_round_trips()
    {
        val give = listOf(8080, 39490, 39491, 39492, 40090, 60999)
        assertEquals(give, parsePortList(formatPortList(give)))
    }

    @Test
    fun agent_reserved_ports_are_the_listen_port()
    {
        assertEquals(listOf(48990), AGENT_RESERVED_PORTS)
        assertEquals(48990, AGENT_API_PORT)
    }

    @Test
    fun plan_reserved_ports()
    {
        val ours = listOf(8332, 39490, 39491, 61000)

        val nothing = planReservedPorts("32768 60999", "", ours)!!
        assertEquals(listOf(39490, 39491), nothing.need)
        assertEquals(listOf(39490, 39491), nothing.missing)
        assertEquals(listOf(39490, 39491), nothing.want)

        val already = planReservedPorts("32768 60999", "39490-39491", ours)!!
        assertEquals(listOf(39490, 39491), already.need)
        assertTrue(already.missing.isEmpty())
        assertEquals(listOf(39490, 39491), already.want)

        val operator = planReservedPorts("32768 60999", "45000", ours)!!
        assertEquals(listOf(39490, 39491), operator.need)
        assertEquals(listOf(39490, 39491), operator.missing)
        assertEquals(listOf(39490, 39491, 45000), operator.want)

        val narrow = planReservedPorts("50000 60999", "", ours)!!
        assertTrue(narrow.need.isEmpty())
        assertTrue(narrow.missing.isEmpty())

        val wide = planReservedPorts("1024 65535", "", ours)!!
        assertEquals(listOf(8332, 39490, 39491, 61000), wide.need)

        assertNull(planReservedPorts("garbage", "", ours))
    }
}
