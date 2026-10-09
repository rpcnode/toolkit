package rpcnode.toolkit.settings.application.get

fun interface UrlProbe
{
    suspend fun reachable(url: String): Boolean
}

suspend fun UrlProbe.snapshotCdnReachable(origin: String): Boolean
{
    val base = origin.trim().trimEnd('/')
    if (base.isEmpty())
    {
        return false
    }
    return reachable("$base/") || reachable("$base/snapshots/")
}
