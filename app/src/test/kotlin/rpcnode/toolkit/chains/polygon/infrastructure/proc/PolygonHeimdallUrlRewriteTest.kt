package rpcnode.toolkit.chains.polygon.infrastructure.proc

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolygonHeimdallUrlRewriteTest
{
    @Test
    fun uncommented_heimdall_block_gets_active_url()
    {
        val raw =
            """
            [txpool]
                nolocals = true

            # [heimdall]
                # url = "http://127.0.0.1:1327"
                # "bor.without" = false
                # grpc-address = ""

            [miner]
                gaslimit = 45000000
            """.trimIndent()
        val out = PolygonConfigPatch.rewriteHeimdallUrl(raw, "http://127.0.0.1:1327")
        assertContains(out, "[heimdall]")
        assertContains(out, """url = "http://127.0.0.1:1327"""")
        assertFalse(out.contains("# [heimdall]"))
        assertTrue(out.contains("[miner]"))
    }
}
