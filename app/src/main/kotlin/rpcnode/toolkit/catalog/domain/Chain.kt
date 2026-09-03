package rpcnode.toolkit.catalog.domain

data class Chain(
    val id: NetworkId,
    val label: String,
    /** Default [id]; arb → arbitrum. */
    val dataRoot: String = "",
    val envs: List<Env>,
)
{
    fun displayLabel(): String = label.trim().ifEmpty { id.value }

    fun root(): String
    {
        val r = dataRoot.trim()
        return r.ifEmpty { id.value }
    }

    fun normalizeEnv(raw: String): EnvId
    {
        val parsed = EnvId.parse(raw) ?: return EnvId.MAINNET
        if (id.value == "avalanche" && parsed.value == "testnet")
        {
            return EnvId.FUJI
        }
        return parsed
    }

    fun env(raw: String): Env?
    {
        val want = normalizeEnv(raw)
        return envs.firstOrNull { it.id == want }
    }
}
