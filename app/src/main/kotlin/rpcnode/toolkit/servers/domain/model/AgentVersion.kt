package rpcnode.toolkit.servers.domain.model

/** Semver-ish: local older than remote. Equal or empty → not outdated. */
fun agentVersionOutdated(localRaw: String, remoteRaw: String): Boolean
{
    val local = stripV(localRaw)
    val remote = stripV(remoteRaw)
    if (local.isEmpty() || remote.isEmpty() || local == remote)
    {
        return false
    }
    val left = local.split('.')
    val right = remote.split('.')
    val n = maxOf(left.size, right.size)
    for (i in 0 until n)
    {
        val a = left.getOrNull(i)?.toIntOrNull() ?: 0
        val b = right.getOrNull(i)?.toIntOrNull() ?: 0
        if (a < b)
        {
            return true
        }
        if (a > b)
        {
            return false
        }
    }
    return false
}

private fun stripV(raw: String): String
{
    val value = raw.trim()
    if (value.length > 1 && (value[0] == 'v' || value[0] == 'V'))
    {
        return value.substring(1)
    }
    return value
}
