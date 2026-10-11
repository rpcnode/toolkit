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

private fun occupant(port: Int): String? = PortHolders.holder(port)

/**
 * Who listens on which port, for the "busy — held by …" hint.
 *
 * `ss -p` has to walk /proc of every process: on a loaded host it takes many seconds (7 s measured at load 64, and the
 * `lsof` fallback 17 s). Asking once per busy port made a check of ten ports last 20–50 s and the panel gave up.
 * So: one `ss -lptn` for the whole table, shared by all callers and reused for [TTL_MS], with a hard time limit —
 * the free/busy answer never waits longer than that for a name. `lsof` is only tried when `ss` does not exist.
 */
object PortHolders
{
    private const val TTL_MS = 15_000L
    private const val LIMIT_SECONDS = 4L
    private val lock = Any()
    private var loaded = false
    private var at = 0L
    private var table: Map<Int, String> = emptyMap()
    @Volatile private var ssMissing = false

    fun holder(
        port: Int,
        nowMs: () -> Long = System::currentTimeMillis,
        run: () -> String? = ::runSsTable,
        fallback: (Int) -> String? = ::occupantFromLsof,
    ): String? = synchronized(lock)
    {
        val now = nowMs()
        if (!loaded || now - at >= TTL_MS)
        {
            table = parse(run().orEmpty())
            loaded = true
            at = nowMs()
        }
        // lsof only when ss is not installed: after an ss timeout it would just be slower still
        table[port] ?: if (ssMissing) fallback(port) else null
    }

    fun reset()
    {
        synchronized(lock) { loaded = false; at = 0L; table = emptyMap(); ssMissing = false }
    }

    fun parse(text: String): Map<Int, String>
    {
        val out = linkedMapOf<Int, String>()
        val re = Regex("""^\s*LISTEN\s+\S+\s+\S+\s+(\S+)\s+\S+\s+users:\(\("([^"]+)",pid=(\d+)""")
        for (line in text.lineSequence())
        {
            val m = re.find(line) ?: continue
            val port = m.groupValues[1].substringAfterLast(':').toIntOrNull() ?: continue
            out.putIfAbsent(port, "${m.groupValues[2]} (pid ${m.groupValues[3]})")
        }
        return out
    }

    private fun runSsTable(): String? = runProcess(listOf("ss", "-lptn"), LIMIT_SECONDS) { ssMissing = true }
}

private fun occupantFromLsof(port: Int): String?
{
    // Only when ss is not installed: lsof is far slower, so it gets the same short leash.
    val text = runProcess(listOf("lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN"), 4) ?: return null
    val match = Regex("""^(\S+)\s+(\d+)""", RegexOption.MULTILINE).find(text) ?: return null
    return "${match.groupValues[1]} (pid ${match.groupValues[2]})"
}

private fun runProcess(command: List<String>, limitSeconds: Long, onMissing: () -> Unit = {}): String?
{
    return try
    {
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        val reader = java.util.concurrent.CompletableFuture.supplyAsync { proc.inputStream.readAllBytes() }
        if (!proc.waitFor(limitSeconds, java.util.concurrent.TimeUnit.SECONDS))
        {
            proc.destroyForcibly()
            return null
        }
        String(reader.get(2, java.util.concurrent.TimeUnit.SECONDS), Charsets.UTF_8).ifBlank { null }
    }
    catch (_: java.io.IOException)
    {
        onMissing()
        null
    }
    catch (_: Exception)
    {
        null
    }
}
