package rpcnode.toolkit.chains.solana.infrastructure.proc

import kotlin.test.Test
import kotlin.test.assertTrue

class SolanaHostTuneTest
{
    @Test
    fun conf_body_sets_udp_buffers_and_nr_open()
    {
        val body = SolanaHostTune.CONF_BODY
        assertTrue(body.contains("net.core.rmem_max = 134217728"))
        assertTrue(body.contains("net.core.wmem_max = 134217728"))
        assertTrue(body.contains("fs.nr_open = 8388608"))
        assertTrue(body.contains("vm.max_map_count = 1000000"))
    }
}
