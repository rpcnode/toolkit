package rpcnode.toolkit.agent.presentation.http

import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * True when nothing is **listening** on [port] (or a reuse-bind succeeds).
 * TIME_WAIT leftovers must not block agent start — Netty uses SO_REUSEADDR.
 */
fun tcpPortFree(host: String, port: Int): Boolean
{
    if (listenPortOccupant(port) != null)
    {
        return false
    }
    return try
    {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(host.ifBlank { "0.0.0.0" }, port))
        }
        true
    }
    catch (_: Exception)
    {
        false
    }
}

/** Null when [host]:[port] can be bound. Otherwise a one-line reason, including the occupant if known. */
fun listenPortBusyMessage(host: String, port: Int): String?
{
    val where = "${host.ifBlank { "0.0.0.0" }}:$port"
    val who = listenPortOccupant(port)
    if (who != null)
    {
        return "cannot bind $where — already in use by $who"
    }
    if (tcpPortFree(host, port))
    {
        return null
    }
    return "cannot bind $where — port in use"
}

fun listenPortOccupant(port: Int): String?
{
    occupantFromSs(port)?.let { return it }
    return occupantFromLsof(port)
}

fun isAddressInUse(error: Throwable): Boolean
{
    var cur: Throwable? = error
    while (cur != null)
    {
        if (cur is java.net.BindException)
        {
            return true
        }
        val msg = cur.message.orEmpty()
        if (
            msg.contains("Address already in use", ignoreCase = true) ||
            msg.contains("already bound", ignoreCase = true)
        )
        {
            return true
        }
        cur = cur.cause
    }
    return false
}

private fun occupantFromSs(port: Int): String?
{
    // Scrape the full listen table — `ss … sport = :N` is unreliable across ss versions.
    val text = runProcess(listOf("ss", "-ltnp")) ?: return null
    val needle = Regex(""":""" + Regex.escape(port.toString()) + """\b""")
    for (line in text.lineSequence())
    {
        if (!needle.containsMatchIn(line))
        {
            continue
        }
        val match = Regex("""users:\(\("([^"]+)",pid=(\d+)""").find(line) ?: continue
        return "${match.groupValues[1]} (pid ${match.groupValues[2]})"
    }
    return null
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
