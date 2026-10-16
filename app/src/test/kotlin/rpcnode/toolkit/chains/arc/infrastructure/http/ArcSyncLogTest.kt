package rpcnode.toolkit.chains.arc.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArcSyncLogTest
{
    @Test
    fun `takes the last peer height from the consensus sync lines`()
    {
        val log = """
            2026-10-13T10:05:02.418982Z  INFO node{moniker=a}:sync{tip_height=1 sync_height=2}: SYNC REQUIRED: Falling behind tip_height=1 sync_height=2 peer_height=66971748
            2026-10-13T10:05:03.003094Z  INFO node{moniker=a}:sync{tip_height=2 sync_height=3}: SYNC REQUIRED: Falling behind tip_height=2 sync_height=3 peer_height=66971749
        """.trimIndent()
        assertEquals(66971749L, ArcSyncLog.peerHeight(log))
    }

    @Test
    fun `no sync line means unknown`()
    {
        assertNull(ArcSyncLog.peerHeight("INFO Started round height=1"))
        assertNull(ArcSyncLog.peerHeight(null))
    }
}
