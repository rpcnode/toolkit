package rpcnode.toolkit.chains.base.infrastructure

/** Static Base OP Stack facts for one env. */
data class BaseCluster(
    val env: String,
    val chainId: String,
    val rethChain: String,
    val networkFlag: String,
    val sequencerHttp: String,
)

object BaseClusters
{
    const val SNAPSHOT_API_URL = "https://chain.base.org/api/snapshots"

    /** Official P2P ENR set from base/node .env.mainnet / .env.sepolia. */
    const val BOOTNODES =
        "enr:-J24QNz9lbrKbN4iSmmjtnr7SjUMk4zB7f1krHZcTZx-JRKZd0kA2gjufUROD6T3sOWDVDnFJRvqBBo62zuF-hYCohOGAYiOoEyEgmlkgnY0gmlwhAPniryHb3BzdGFja4OFQgCJc2VjcDI1NmsxoQKNVFlCxh_B-716tTs-h1vMzZkSs1FTu_OYTNjgufplG4N0Y3CCJAaDdWRwgiQG," +
            "enr:-J24QH-f1wt99sfpHy4c0QJM-NfmsIfmlLAMMcgZCUEgKG_BBYFc6FwYgaMJMQN5dsRBJApIok0jFn-9CS842lGpLmqGAYiOoDRAgmlkgnY0gmlwhLhIgb2Hb3BzdGFja4OFQgCJc2VjcDI1NmsxoQJ9FTIv8B9myn1MWaC_2lJ-sMoeCDkusCsk4BYHjjCq04N0Y3CCJAaDdWRwgiQG," +
            "enr:-J24QDXyyxvQYsd0yfsN0cRr1lZ1N11zGTplMNlW4xNEc7LkPXh0NAJ9iSOVdRO95GPYAIc6xmyoCCG6_0JxdL3a0zaGAYiOoAjFgmlkgnY0gmlwhAPckbGHb3BzdGFja4OFQgCJc2VjcDI1NmsxoQJwoS7tzwxqXSyFL7g0JM-KWVbgvjfB8JA__T7yY_cYboN0Y3CCJAaDdWRwgiQG," +
            "enr:-J24QHmGyBwUZXIcsGYMaUqGGSl4CFdx9Tozu-vQCn5bHIQbR7On7dZbU61vYvfrJr30t0iahSqhc64J46MnUO2JvQaGAYiOoCKKgmlkgnY0gmlwhAPnCzSHb3BzdGFja4OFQgCJc2VjcDI1NmsxoQINc4fSijfbNIiGhcgvwjsjxVFJHUstK9L1T8OTKUjgloN0Y3CCJAaDdWRwgiQG," +
            "enr:-J24QG3ypT4xSu0gjb5PABCmVxZqBjVw9ca7pvsI8jl4KATYAnxBmfkaIuEqy9sKvDHKuNCsy57WwK9wTt2aQgcaDDyGAYiOoGAXgmlkgnY0gmlwhDbGmZaHb3BzdGFja4OFQgCJc2VjcDI1NmsxoQIeAK_--tcLEiu7HvoUlbV52MspE0uCocsx1f_rYvRenIN0Y3CCJAaDdWRwgiQG"

    fun lookup(env: String): BaseCluster
    {
        return when (env.trim().lowercase())
        {
            "sepolia" -> BaseCluster(
                env = "sepolia",
                chainId = "84532",
                rethChain = "base-sepolia",
                networkFlag = "base-sepolia",
                sequencerHttp = "https://sepolia-sequencer.base.org",
            )
            else -> BaseCluster(
                env = "mainnet",
                chainId = "8453",
                rethChain = "base",
                networkFlag = "base",
                sequencerHttp = "https://mainnet-sequencer.base.org",
            )
        }
    }

    /** Base sepolia → ethereum sepolia L1; else mainnet. */
    fun l1Env(env: String): String
    {
        val e = env.trim().lowercase()
        return if (e == "sepolia" || e == "testnet") "sepolia" else "mainnet"
    }

    fun normalizeSnapshotFlavor(raw: String?): String
    {
        return when (raw?.trim()?.lowercase())
        {
            "full" -> "full"
            "minimal" -> "minimal"
            else -> "archive"
        }
    }

    /** Official manifest size (GiB, 2026-08-26). */
    fun snapshotSizeGiB(env: String, flavor: String): Long
    {
        val e = lookup(env).env
        val f = normalizeSnapshotFlavor(flavor)
        return if (e == "sepolia")
        {
            when (f)
            {
                "minimal" -> 283L
                "full" -> 828L
                else -> 956L
            }
        }
        else
        {
            when (f)
            {
                "minimal" -> 677L
                "full" -> 3145L
                else -> 3711L
            }
        }
    }

    /** Restore gate: manifest + 20% buffer. */
    fun snapshotRequiredGiB(env: String, flavor: String): Long =
        snapshotSizeGiB(env, flavor) * 12 / 10
}
