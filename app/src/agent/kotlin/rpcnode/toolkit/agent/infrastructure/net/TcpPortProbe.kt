package rpcnode.toolkit.agent.infrastructure.net

import java.net.InetSocketAddress
import java.net.ServerSocket
import rpcnode.toolkit.agent.application.ports.PortCheck
import rpcnode.toolkit.agent.application.ports.PortProbe

/**
 * Binds to `0.0.0.0:port` to test availability — deliberately independent of the agent's own
 * listen address, since these are the *node's* ports, not the agent's.
 *
 * The panel checks the same port list from more than one place at once (the ops aside panel and
 * the install wizard both hit `/ports/check` on page load). Two overlapping bind attempts on the
 * *same* port are a genuine race — only one can hold the socket at a time — so without a lock
 * the loser sees a false "busy" even though nothing is really listening. [bindLock] makes every
 * bind attempt on this agent happen one at a time.
 */
class TcpPortProbe : PortProbe
{
    override fun probe(port: Int): PortCheck
    {
        val free = tcpPortFree(port)
        return PortCheck(port = port, free = free, holder = if (free) null else occupant(port))
    }
}

private val bindLock = Any()

private fun tcpPortFree(port: Int): Boolean = synchronized(bindLock)
{
    try
    {
        ServerSocket().use { socket ->
            // SO_REUSEADDR mirrors how the client programs themselves bind. Without it, a bind
            // fails while any old connection on this port sits in TIME_WAIT (peers on P2P /
            // HTTP API ports churn constantly), flapping the check free/busy even though no
            // process is actually listening.
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", port))
        }
        true
    }
    catch (_: Exception)
    {
        false
    }
}

private fun occupant(port: Int): String?
{
    occupantFromSs(port)?.let { return it }
    return occupantFromLsof(port)
}

private fun occupantFromSs(port: Int): String?
{
    val text = runProcess(listOf("ss", "-lptn", "sport = :$port")) ?: return null
    val match = Regex("""users:\(\("([^"]+)",pid=(\d+)""").find(text) ?: return null
    return "${match.groupValues[1]} (pid ${match.groupValues[2]})"
}

private fun occupantFromLsof(port: Int): String?
{
    val text = runProcess(listOf("lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN")) ?: return null
    val match = Regex("""^(\S+)\s+(\d+)""", RegexOption.MULTILINE).find(text) ?: return null
    return "${match.groupValues[1]} (pid ${match.groupValues[2]})"
}

private fun runProcess(command: List<String>): String?
{
    return try
    {
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        out.ifBlank { null }
    }
    catch (_: Exception)
    {
        null
    }
}
