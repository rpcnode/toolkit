package rpcnode.toolkit.nodes.application.config

/**
 * Re-runs the Clients step for a node: downloads/pins the client, patches its config and copies
 * both into the node's current node_dir.
 *
 * node_dir depends on the snapshot type (Lite → `…/litefullnode`), which is chosen on the Snapshot
 * step — after Clients already synced into the default dir. Without a re-sync the binary and
 * config stay behind in the old dir and Start fails with "launch entry missing".
 */
fun interface EnsureNodeClients
{
    /** Returns null on success, otherwise a human-readable reason. */
    suspend fun ensure(nodeId: String, installOptionsJson: String?): String?
}

fun ApplyNodeClientConfigUseCase.asEnsureNodeClients(): EnsureNodeClients = EnsureNodeClients { nodeId, options ->
    when (val r = this(nodeId, options))
    {
        is ApplyNodeClientConfigResult.Applied -> null
        is ApplyNodeClientConfigResult.SyncFailed -> r.message.ifBlank { r.error }
        is ApplyNodeClientConfigResult.AgentUnreachable -> r.detail.ifBlank { "agent unreachable" }
        else -> "client sync not possible: ${r::class.simpleName}"
    }
}
