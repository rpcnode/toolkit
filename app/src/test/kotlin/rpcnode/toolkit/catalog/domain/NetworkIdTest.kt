package rpcnode.toolkit.catalog.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkIdTest
{
    @Test
    fun parse_empty_is_not_an_id()
    {
        assertNull(NetworkId.parse(""))
        assertNull(NetworkId.parse("   "))
    }

    @Test
    fun parse_is_case_insensitive()
    {
        assertEquals(NetworkId.BITCOIN, NetworkId.parse("Bitcoin"))
        assertEquals(NetworkId.parse("bitcoin"), NetworkId.parse("BITCOIN"))
    }

    @Test
    fun tron_parses_to_the_shared_constant()
    {
        assertEquals(NetworkId.TRON, NetworkId.parse("TRON"))
    }
}
