package rpcnode.toolkit.chains.solana.infrastructure

/** Static Agave cluster facts for one env (Go `cluster.go`). */
data class SolanaCluster(
    val cluster: String,
    val genesis: String,
    val entrypoints: List<String>,
    val knownValidators: List<String> = emptyList(),
    val onlyKnownRpc: Boolean = false,
    val p2pRangeSpan: Int = 26,
)

object SolanaClusters
{
    fun lookup(env: String): SolanaCluster
    {
        return when (env.trim().lowercase())
        {
            "testnet" -> SolanaCluster(
                cluster = "testnet",
                genesis = "4uhcVJyU9pJkvQyS88uRDiswHXSCkY3zQawwpjk2NsNY",
                entrypoints = listOf(
                    "entrypoint.testnet.solana.com:8001",
                    "entrypoint2.testnet.solana.com:8001",
                    "entrypoint3.testnet.solana.com:8001",
                ),
            )
            "devnet" -> SolanaCluster(
                cluster = "devnet",
                genesis = "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG",
                entrypoints = listOf(
                    "entrypoint.devnet.solana.com:8001",
                    "entrypoint2.devnet.solana.com:8001",
                    "entrypoint3.devnet.solana.com:8001",
                ),
            )
            else -> SolanaCluster(
                cluster = "mainnet-beta",
                genesis = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d",
                entrypoints = listOf(
                    "entrypoint.mainnet-beta.solana.com:8001",
                    "entrypoint2.mainnet-beta.solana.com:8001",
                    "entrypoint3.mainnet-beta.solana.com:8001",
                    "entrypoint4.mainnet-beta.solana.com:8001",
                    "entrypoint5.mainnet-beta.solana.com:8001",
                ),
                knownValidators = listOf(
                    "7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2",
                    "GdnSyH3YtwcxFvQrVVJMm1JhTS4QVX7MFsX56uJLUfiZ",
                    "DE1bawNcRJB9rVm3buyMVfr8mBEoyyu73NBovf2oXJsJ",
                    "CakcnaRDHka2gXyfbEd2d3xsvkJkqsLw2akB3zsN1D2S",
                ),
                onlyKnownRpc = true,
            )
        }
    }

    fun normalizeEnv(env: String): String
    {
        val e = env.trim().lowercase()
        return if (e.isEmpty()) "mainnet" else e
    }

    fun normalizeMode(raw: String?): String
    {
        return when (raw?.trim()?.lowercase())
        {
            "archive" -> "archive"
            else -> "full"
        }
    }

    /** Agave `--dynamic-port-range` MIN-MAX inclusive. */
    fun p2pRange(base: Int, span: Int): String
    {
        if (base <= 0)
        {
            return ""
        }
        val s = span.coerceAtLeast(0)
        return "$base-${base + s}"
    }
}
