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
        val AVALANCHE = NetworkId("avalanche")
        val ARC = NetworkId("arc")
        val ROBINHOOD = NetworkId("robinhood")
        val OPTIMISM = NetworkId("optimism")
        val ETC = NetworkId("etc")
        val STELLAR = NetworkId("stellar")
        val APTOS = NetworkId("aptos")
        val CARDANO = NetworkId("cardano")
        val COSMOS = NetworkId("cosmos")
        val OSMOSIS = NetworkId("osmosis")
        val INJECTIVE = NetworkId("injective")
        val SEI = NetworkId("sei")
        val CELESTIA = NetworkId("celestia")
        val XRPLEVM = NetworkId("xrplevm")
        val BERACHAIN = NetworkId("berachain")
        val DATA = NetworkId("data")
        val FLARE = NetworkId("flare")
        val FLUENT = NetworkId("fluent")
        val GNOSIS = NetworkId("gnosis")
        val GRAVITY = NetworkId("gravity")
        val JOC = NetworkId("joc")
        val KAIA = NetworkId("kaia")
        val MONAD = NetworkId("monad")
        val SONIC = NetworkId("sonic")
        val TEMPO = NetworkId("tempo")
        val VANA = NetworkId("vana")
        val ALEO = NetworkId("aleo")
        val FUEL = NetworkId("fuel")
        val NEAR = NetworkId("near")
        val PEAQ = NetworkId("peaq")
        val POLKADOT = NetworkId("polkadot")
        val STACKS = NetworkId("stacks")
        val INK = NetworkId("ink")
        val LISK = NetworkId("lisk")
        val MODE = NetworkId("mode")
        val SONEIUM = NetworkId("soneium")
        val UNICHAIN = NetworkId("unichain")
        val WORLDCHAIN = NetworkId("worldchain")
        val ZORA = NetworkId("zora")
        val STARKNET = NetworkId("starknet")
        val XAI = NetworkId("xai")

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
