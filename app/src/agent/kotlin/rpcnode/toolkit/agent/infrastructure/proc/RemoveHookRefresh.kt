package rpcnode.toolkit.agent.infrastructure.proc

import java.nio.file.Path
import rpcnode.toolkit.chains.ton.infrastructure.TonRemoveHookRefresh

/** Brings the remove hook of a node up to date just before the agent runs it (the hook is written at Start, so it may be old). */
object RemoveHookRefresh
{
    /** How many hooks were rewritten. */
    fun refresh(network: String, dirs: List<Path>): Int =
        when (network.trim().lowercase())
        {
            "ton" -> dirs.count { TonRemoveHookRefresh.refresh(it) }
            else -> 0
        }
}
