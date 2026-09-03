package rpcnode.toolkit.networks.infrastructure.filesystem

/**
 * Path-segment safety for the clients dest tree (`dest/<network>/<env>/...`).
 * Shared between `networks` and `clients` — same traversal protection, one place.
 */
object ClientDestPaths
{
    /** Lowercase alnum/-/_ only. Rejects empty, slashes, `..`. */
    fun safeSegment(raw: String): String?
    {
        val s = raw.trim().lowercase()
        if (s.isEmpty())
        {
            return null
        }
        val valid = s.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
        if (!valid)
        {
            return null
        }
        return s
    }
}
