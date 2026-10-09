package rpcnode.toolkit.agent.domain.model

/** Host-agent HTTP listen port. Must stay in sync with AgentSystemInstall. */
const val AGENT_API_PORT = 48990

/** Ports taken out of the kernel ephemeral allocator. Static — not a scanned window. */
val AGENT_RESERVED_PORTS: List<Int> = listOf(AGENT_API_PORT)

data class EphemeralPortRange(
    val lo: Int,
    val hi: Int,
)
{
    fun valid(): Boolean = lo > 0 && hi >= lo

    fun has(port: Int): Boolean = valid() && port in lo..hi

    override fun toString(): String = if (valid()) "$lo-$hi" else "unknown"
}

data class ReservedPortsPlan(
    val want: List<Int>,
    val need: List<Int>,
    val missing: List<Int>,
    val range: EphemeralPortRange,
)

fun parsePortRange(raw: String): EphemeralPortRange?
{
    val fields = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (fields.size != 2)
    {
        return null
    }
    val lo = fields[0].toIntOrNull() ?: return null
    val hi = fields[1].toIntOrNull() ?: return null
    val range = EphemeralPortRange(lo, hi)
    return range.takeIf { it.valid() }
}

fun parsePortList(raw: String): List<Int>
{
    val tokens = raw.split(',', ' ', '\t', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    val out = ArrayList<Int>()
    for (tok in tokens)
    {
        val (lo, hi) = parsePortToken(tok) ?: continue
        for (p in lo..hi)
        {
            out.add(p)
        }
    }
    return sortUniqPorts(out)
}

fun formatPortList(ports: Iterable<Int>): String
{
    val sorted = sortUniqPorts(ports)
    if (sorted.isEmpty())
    {
        return ""
    }
    val parts = ArrayList<String>()
    var start = sorted[0]
    var prev = sorted[0]
    fun flush()
    {
        parts.add(if (start == prev) start.toString() else "$start-$prev")
    }
    for (p in sorted.drop(1))
    {
        if (p == prev + 1)
        {
            prev = p
            continue
        }
        flush()
        start = p
        prev = p
    }
    flush()
    return parts.joinToString(",")
}

fun sortUniqPorts(ports: Iterable<Int>): List<Int>
{
    return ports.filter { it in 1..65_535 }.toSortedSet().toList()
}

/**
 * Which of [ours] the kernel could steal as ephemeral source ports, and the merged
 * `ip_local_reserved_ports` value. A write replaces the whole list, so [want] is a union
 * with whatever is already reserved.
 */
fun planReservedPorts(rangeRaw: String, reservedRaw: String, ours: List<Int>): ReservedPortsPlan?
{
    val range = parsePortRange(rangeRaw) ?: return null
    val need = sortUniqPorts(ours).filter { range.has(it) }
    val existing = parsePortList(reservedRaw)
    val have = existing.toSet()
    val missing = need.filter { it !in have }
    val want = sortUniqPorts(existing + need)
    return ReservedPortsPlan(want = want, need = need, missing = missing, range = range)
}

private fun parsePortToken(tok: String): Pair<Int, Int>?
{
    val dash = tok.indexOf('-')
    if (dash > 0)
    {
        val a = tok.substring(0, dash).trim().toIntOrNull() ?: return null
        val b = tok.substring(dash + 1).trim().toIntOrNull() ?: return null
        if (b < a)
        {
            return null
        }
        return a to b
    }
    val p = tok.toIntOrNull() ?: return null
    if (p <= 0)
    {
        return null
    }
    return p to p
}
