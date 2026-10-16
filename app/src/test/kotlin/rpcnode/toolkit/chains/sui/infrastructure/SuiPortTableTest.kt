package rpcnode.toolkit.chains.sui.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog

class SuiPortTableTest
{
    @Test
    fun the_table_matches_the_catalog()
    {
        val programs = YamlClientProgramCatalog().all().filter { it.network == NetworkId.SUI }
        for (p in programs)
        {
            val ports = SuiPortTable.forEnv(p.env.value)
            val byRole = p.ports.associate { it.role to it.port }
            assertEquals(ports.http, byRole["http"], "${p.env.value} http")
            assertEquals(ports.metrics, byRole["metrics"], "${p.env.value} metrics")
            assertEquals(ports.p2p, byRole["p2p"], "${p.env.value} p2p")
        }
    }

    @Test
    fun testnet_p2p_stays_off_8085_where_redpanda_console_listens()
    {
        assertNotEquals(8085, SuiPortTable.forEnv("testnet").p2p)
    }
}
