package rpcnode.toolkit.chains.xrpl.infrastructure

/** Env + UNL / bootstrap peers for stock xrpld. */
data class XrplCluster(
    val env: String,
    /** Empty = mainnet default; "1" = XRPL Testnet (AltNet). */
    val networkId: String,
    val ipsFixed: List<String>,
    val ips: List<String> = emptyList(),
)

object XrplClusters
{
    private val MAINNET_HUBS = listOf(
        "r.ripple.com 51235",
        "sahyadri.isrdc.in 51235",
        "hubs.xrpkuwait.com 51235",
        "hub.xrpl-commons.org 51235",
    )

    private val MAINNET = XrplCluster(
        env = "mainnet",
        networkId = "",
        ips = MAINNET_HUBS,
        // Official full-history pool — peer-to-peer backfill needs a direct history peer.
        ipsFixed = MAINNET_HUBS + "s2.ripple.com 51235",
    )

    private val TESTNET = XrplCluster(
        env = "testnet",
        networkId = "1",
        ipsFixed = listOf("s.altnet.rippletest.net 51235"),
    )

    fun normalizeEnv(raw: String): String
    {
        val e = raw.trim().lowercase()
        return if (e == "testnet") "testnet" else "mainnet"
    }

    fun lookup(raw: String): XrplCluster =
        if (normalizeEnv(raw) == "testnet") TESTNET else MAINNET

    /** Genesis ledger index — 32570 on mainnet, 1 on testnet. */
    fun genesisLedger(raw: String): Long =
        if (normalizeEnv(raw) == "testnet") 1L else 32_570L
}
