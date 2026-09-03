package rpcnode.toolkit.nodes.application.disks

import rpcnode.toolkit.nodes.domain.model.HostDiskCatalog

fun interface HostDiskReader
{
    /** GET /api/v1/host/disks on the host agent. Null when unreachable. */
    suspend fun read(agentUrl: String, token: String): HostDiskCatalog?
}
