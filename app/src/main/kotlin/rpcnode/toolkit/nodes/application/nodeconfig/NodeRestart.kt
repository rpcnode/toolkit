package rpcnode.toolkit.nodes.application.nodeconfig

import rpcnode.toolkit.nodes.application.process.ControlNodeProcessResult
import rpcnode.toolkit.nodes.application.process.ControlNodeProcessUseCase

/** Restart used after a config save: full Start (re-renders the unit, restarts it). Null = ok. */
fun ControlNodeProcessUseCase.asNodeRestart(): suspend (String) -> String? = { nodeId ->
    when (val r = start(nodeId))
    {
        is ControlNodeProcessResult.Ok -> null
        ControlNodeProcessResult.NotFound -> "node not found"
        ControlNodeProcessResult.ServerNotFound -> "server not found"
        ControlNodeProcessResult.AgentUnreachable -> "host agent did not answer"
        ControlNodeProcessResult.InvalidAgentKey -> "invalid agent key"
        is ControlNodeProcessResult.Failed -> r.message.ifBlank { r.error }
    }
}
