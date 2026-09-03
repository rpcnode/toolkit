package rpcnode.toolkit.chains.solana.infrastructure

import java.net.DatagramSocket
import java.net.InetSocketAddress

/** Catalog-fixed ports (clients/solana.yml) — starter uses the same numbers. */
data class SolanaPorts(
    val http: Int,
    val p2p: Int,
)

object SolanaPortTable
{
    fun forEnv(env: String): SolanaPorts
    {
        return when (env.trim().lowercase())
        {
            "testnet" -> SolanaPorts(http = 8891, p2p = 8100)
            "devnet" -> SolanaPorts(http = 8893, p2p = 8200)
            else -> SolanaPorts(http = 8899, p2p = 8000)
        }
    }

    /**
     * Agave gossip/TVU bind a contiguous UDP range (`--dynamic-port-range`).
     * Returns how many ports in [base, base+span] can be bound locally (best-effort).
     */
    fun countBindableUdp(base: Int, span: Int): Int
    {
        if (base <= 0 || span < 0)
        {
            return 0
        }
        var ok = 0
        for (port in base..(base + span))
        {
            try
            {
                DatagramSocket(null).use { sock ->
                    sock.reuseAddress = true
                    sock.bind(InetSocketAddress("0.0.0.0", port))
                }
                ok++
            }
            catch (_: Exception)
            {
                // occupied / permission
            }
        }
        return ok
    }

    /** Fail Start early when the catalog P2P window cannot accept UDP binds. */
    fun requireUdpRange(base: Int, span: Int): String?
    {
        val need = (span + 1).coerceAtLeast(1)
        val got = countBindableUdp(base, span)
        if (got >= need)
        {
            return null
        }
        return "No free UDP ports in $base-${base + span} " +
            "(bindable=$got/$need). Free the range or change P2P in chains/solana/clients.yml — " +
            "Agave panics with: No available UDP ports in ($base, ${base + span})"
    }
}
