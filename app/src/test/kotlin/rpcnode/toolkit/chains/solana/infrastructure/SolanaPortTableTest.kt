package rpcnode.toolkit.chains.solana.infrastructure

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SolanaPortTableTest
{
    @Test
    fun for_env_matches_clients_yml()
    {
        assertTrue(SolanaPortTable.forEnv("testnet").p2p == 8100)
        assertTrue(SolanaPortTable.forEnv("mainnet").http == 8899)
    }

    @Test
    fun require_udp_range_ok_on_ephemeral_high_ports()
    {
        // High ephemeral window is usually free in CI/dev hosts.
        val base = 51_000
        val span = 2
        assertNull(SolanaPortTable.requireUdpRange(base, span))
        assertTrue(SolanaPortTable.countBindableUdp(base, span) >= span + 1)
    }
}
