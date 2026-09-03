package rpcnode.toolkit.agent.infrastructure.net

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TcpPortProbeTest
{
    /**
     * Regression for the panel checking the same free port from two places at once (ops aside
     * panel + install wizard) — before the probe serialized its own binds, two overlapping
     * checks on the same port could race and the loser would falsely report "busy".
     */
    @Test
    fun concurrent_checks_of_the_same_free_port_never_see_a_false_busy()
    {
        val port = findClosedPort()
        val probe = TcpPortProbe()
        val pool = Executors.newFixedThreadPool(8)
        try
        {
            val results = (1..40).map { pool.submit<Boolean> { probe.probe(port).free } }.map { it.get() }
            assertTrue(results.all { it }, "expected every concurrent check of a free port to report free")
        }
        finally
        {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }


    @Test
    fun reports_a_bound_listening_socket_as_busy()
    {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress("0.0.0.0", 0))
        try
        {
            val port = server.localPort
            val result = TcpPortProbe().probe(port)

            assertFalse(result.free)
            assertEquals(port, result.port)
        }
        finally
        {
            server.close()
        }
    }

    @Test
    fun reports_an_unbound_port_as_free()
    {
        val port = findClosedPort()

        val result = TcpPortProbe().probe(port)

        assertTrue(result.free)
        assertEquals(null, result.holder)
    }

    /** Grab an ephemeral port then release it immediately — free right after close(). */
    private fun findClosedPort(): Int
    {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", 0))
            return socket.localPort
        }
    }
}
