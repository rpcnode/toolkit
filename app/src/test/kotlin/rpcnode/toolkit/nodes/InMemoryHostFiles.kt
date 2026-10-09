package rpcnode.toolkit.nodes

import rpcnode.toolkit.nodes.application.nodeconfig.HostFileRead
import rpcnode.toolkit.nodes.application.nodeconfig.HostFileWrite
import rpcnode.toolkit.nodes.application.nodeconfig.HostFiles

/** Host files kept in memory; [reachable]=false behaves like an agent that does not answer. */
class InMemoryHostFiles(
    val files: MutableMap<String, String> = mutableMapOf(),
    var reachable: Boolean = true,
) : HostFiles
{
    val writes = mutableListOf<Pair<String, String>>()

    override suspend fun read(agentUrl: String, token: String, path: String): HostFileRead? =
        if (!reachable) null else HostFileRead.Ok(path, exists = files.containsKey(path), content = files[path].orEmpty())

    override suspend fun write(agentUrl: String, token: String, path: String, content: String): HostFileWrite?
    {
        if (!reachable) return null
        files[path] = content
        writes += path to content
        return HostFileWrite.Ok(path)
    }
}
