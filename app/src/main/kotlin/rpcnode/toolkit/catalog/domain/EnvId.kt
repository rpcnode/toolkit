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
        val MORDOR = EnvId("mordor")
        val PREPROD = EnvId("preprod")
        val PREVIEW = EnvId("preview")
        val PASEO = EnvId("paseo")
        val WESTEND = EnvId("westend")
        val AGUNG = EnvId("agung")
        val CHIADO = EnvId("chiado")
        val BEPOLIA = EnvId("bepolia")
        val AENEID = EnvId("aeneid")
        val KAIROS = EnvId("kairos")
        val MODERATO = EnvId("moderato")
        val SONGBIRD = EnvId("songbird")
        val COSTON2 = EnvId("coston2")
        val FLARE = EnvId("flare")
        val MOKSHA = EnvId("moksha")

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
