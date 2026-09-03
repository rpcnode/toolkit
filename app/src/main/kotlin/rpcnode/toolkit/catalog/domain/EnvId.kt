package rpcnode.toolkit.catalog.domain

@JvmInline
value class EnvId private constructor(val value: String)
{
    companion object
    {
        val MAINNET = EnvId("mainnet")
        val TESTNET = EnvId("testnet")
        val TESTNET4 = EnvId("testnet4")
        val SIGNET = EnvId("signet")
        val REGTEST = EnvId("regtest")
        val FUJI = EnvId("fuji")
        val NILE = EnvId("nile")
        val SHASTA = EnvId("shasta")
        val SEPOLIA = EnvId("sepolia")
        val HOODI = EnvId("hoodi")
        val DEVNET = EnvId("devnet")
        val AMOY = EnvId("amoy")

        fun parse(raw: String): EnvId?
        {
            val n = raw.trim().lowercase()
            if (n.isEmpty())
            {
                return null
            }
            return EnvId(n)
        }
    }
}
