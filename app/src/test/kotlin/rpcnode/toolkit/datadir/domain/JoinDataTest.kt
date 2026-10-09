package rpcnode.toolkit.datadir.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class JoinDataTest
{
    @Test
    fun table()
    {
        val tests = listOf(
            Triple("", emptyArray<String>(), "/data/rpcnode"),
            Triple(".", emptyArray(), "/data/rpcnode"),
            Triple("/", emptyArray(), "/data/rpcnode"),
            Triple("/data", emptyArray(), "/data/rpcnode"),
            Triple("  /data  ", emptyArray(), "/data/rpcnode"),
            Triple("/mnt/nvme", arrayOf("tron", "nile"), "/mnt/nvme/rpcnode/tron/nile"),
            Triple("/data", arrayOf("bitcoin", "mainnet"), "/data/rpcnode/bitcoin/mainnet"),
        )
        for ((mount, parts, want) in tests)
        {
            assertEquals(want, joinData(mount, *parts), "joinData($mount, ${parts.joinToString()})")
        }
    }
}
