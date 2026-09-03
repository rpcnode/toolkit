package rpcnode.toolkit.agent.presentation.http

import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListenPortTest
{
    @Test
    fun tcp_port_free_is_false_when_bound()
    {
        ServerSocket().use { taken ->
            taken.bind(InetSocketAddress("127.0.0.1", 0))
            val port = taken.localPort
            assertFalse(tcpPortFree("127.0.0.1", port))
        }
        ServerSocket().use { probe ->
            probe.bind(InetSocketAddress("127.0.0.1", 0))
            val free = probe.localPort
            probe.close()
            assertTrue(tcpPortFree("127.0.0.1", free))
        }
    }

    @Test
    fun busy_message_when_bound()
    {
        ServerSocket().use { taken ->
            taken.bind(InetSocketAddress("127.0.0.1", 0))
            val port = taken.localPort
            val msg = listenPortBusyMessage("127.0.0.1", port)
            assertTrue(msg != null && msg.contains(":$port"))
            assertTrue(msg.contains("in use"))
        }
        ServerSocket().use { probe ->
            probe.bind(InetSocketAddress("127.0.0.1", 0))
            val free = probe.localPort
            probe.close()
            assertTrue(listenPortBusyMessage("127.0.0.1", free) == null)
        }
    }

    @Test
    fun bind_exception_is_address_in_use()
    {
        assertTrue(isAddressInUse(BindException("Address already in use")))
        assertTrue(isAddressInUse(IllegalStateException("wrap", BindException("already bound"))))
        assertFalse(isAddressInUse(IllegalStateException("boom")))
    }
}
