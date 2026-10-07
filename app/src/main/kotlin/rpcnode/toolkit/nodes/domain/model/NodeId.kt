package rpcnode.toolkit.nodes.domain.model

import java.util.UUID

@JvmInline
value class NodeId private constructor(val value: String)
{
    companion object
    {
        fun parse(raw: String): NodeId?
        {
            val n = raw.trim()
            if (n.isEmpty())
            {
                return null
            }
            return NodeId(n)
        }

        fun generate(): NodeId = NodeId(UUID.randomUUID().toString())
    }
}
