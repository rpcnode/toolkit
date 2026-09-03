package rpcnode.toolkit.catalog.domain

@JvmInline
value class NetworkId private constructor(val value: String)
{
    companion object
    {
        val BITCOIN = NetworkId("bitcoin")
        val LTC = NetworkId("ltc")
        val DOGE = NetworkId("doge")
        val DASH = NetworkId("dash")
        val BCH = NetworkId("bch")
        val TRON = NetworkId("tron")
        val ZCASH = NetworkId("zcash")
        val ETHEREUM = NetworkId("ethereum")
        val SOLANA = NetworkId("solana")
        val POLYGON = NetworkId("polygon")
        val BSC = NetworkId("bsc")
        val BASE = NetworkId("base")
        val ARB = NetworkId("arb")
        val SUI = NetworkId("sui")
        val HYPERLIQUID = NetworkId("hyperliquid")
        val TON = NetworkId("ton")
        val XRPL = NetworkId("xrpl")

        fun parse(raw: String): NetworkId?
        {
            val n = raw.trim().lowercase()
            if (n.isEmpty())
            {
                return null
            }
            return NetworkId(n)
        }
    }
}
