package rpcnode.toolkit.chains.avalanche.infrastructure

/** Env helpers for Avalanche (`--network-id` values). */
object AvalancheClusters
{
    fun normalizeEnv(raw: String): String
    {
        return if (raw.trim().lowercase() == "fuji") "fuji" else "mainnet"
    }

    /** avalanchego `--network-id` for the env. */
    fun networkFlag(raw: String): String = normalizeEnv(raw)
}

/** Catalog-fixed ports per env — must match `chains/avalanche/clients.yml`. */
data class AvalanchePorts(val http: Int, val staking: Int)

object AvalanchePortTable
{
    fun forEnv(env: String): AvalanchePorts
    {
        return when (AvalancheClusters.normalizeEnv(env))
        {
            "fuji" -> AvalanchePorts(http = 9660, staking = 9661)
            else -> AvalanchePorts(http = 9650, staking = 9651)
        }
    }
}
