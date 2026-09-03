package rpcnode.toolkit.servers.domain.model

import java.util.UUID

@JvmInline
value class ServerId private constructor(val value: String)
{
    companion object
    {
        fun parse(raw: String): ServerId?
        {
            val n = raw.trim()
            if (n.isEmpty())
            {
                return null
            }
            return ServerId(n)
        }

        fun generate(): ServerId = ServerId(UUID.randomUUID().toString())
    }
}
