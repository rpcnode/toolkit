package rpcnode.toolkit.agent.application.node

import rpcnode.toolkit.agent.domain.model.RunningNode

interface RunningNodeRegistry
{
    fun upsert(node: RunningNode)
    fun remove(nodeId: String)
    fun get(nodeId: String): RunningNode?
    fun list(): List<RunningNode>
}
