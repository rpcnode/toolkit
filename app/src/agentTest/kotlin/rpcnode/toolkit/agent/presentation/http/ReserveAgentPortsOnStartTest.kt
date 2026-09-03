package rpcnode.toolkit.agent.presentation.http

import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.agent.application.reserve.FakeReservedPortsHost
import rpcnode.toolkit.agent.application.reserve.ReserveAgentPortsUseCase
import rpcnode.toolkit.agent.infrastructure.catalog.CatalogFixedPortsReader

/** No `clients` resource on this loader — isolates the "just the agent port" case from whatever
 *  the shipped YAML catalog happens to contain. */
private val noCatalogResources = CatalogFixedPortsReader(URLClassLoader(emptyArray(), null))

class ReserveAgentPortsOnStartTest
{
    @Test
    fun reserves_the_static_agent_port()
    {
        val fake = FakeReservedPortsHost(
            mutableMapOf(
                ReserveAgentPortsUseCase.LOCAL_PORT_RANGE_PROC to "32768 60999",
                ReserveAgentPortsUseCase.RESERVED_PORTS_PROC to "",
            ),
        )
        val st = reserveAgentPortsOnStart(
            rangeFileEnv = "/tmp/rpcnode-agent.ports",
            confEnv = "/tmp/99-rpcnode-agent-ports.conf",
            host = fake,
            catalogPorts = noCatalogResources,
        )
        assertTrue(st.ok)
        assertEquals("48990", fake.readFile(ReserveAgentPortsUseCase.RESERVED_PORTS_PROC))
        assertEquals("48990\n", fake.readFile("/tmp/rpcnode-agent.ports"))
        assertTrue(fake.readFile("/tmp/99-rpcnode-agent-ports.conf").orEmpty().contains("48990"))
    }

    @Test
    fun also_reserves_the_shipped_client_catalog_ports()
    {
        val fake = FakeReservedPortsHost(
            mutableMapOf(
                ReserveAgentPortsUseCase.LOCAL_PORT_RANGE_PROC to "32768 60999",
                ReserveAgentPortsUseCase.RESERVED_PORTS_PROC to "",
            ),
        )
        val st = reserveAgentPortsOnStart(
            rangeFileEnv = "/tmp/rpcnode-agent-catalog.ports",
            confEnv = "/tmp/99-rpcnode-agent-catalog-ports.conf",
            host = fake,
        )
        assertTrue(st.ok)
        // TRON mainnet gRPC (50051) sits inside the ephemeral range and isn't the agent's own
        // port — ports outside the range (e.g. 18888) never need reserving in the first place.
        assertTrue(50051 in st.want)
        assertTrue(48990 in st.want)
    }
}
