package rpcnode.toolkit.chains.sui.infrastructure

/** A state-sync seed peer of `p2p-config.seed-peers` (anemo over UDP): `/dns/<host>/udp/<port>` and its public key. */
data class SuiSeedPeer(val address: String, val peerId: String)

/**
 * Env + checkpoint archive / genesis helpers for Sui. [seedPeers] are the ones of docs.sui.io/guides/operator/sui-full-node
 * (checked 2026-10-14: the Mysten-run ones also against MystenLabs/sui `scripts/compatibility/fullnode-sync.sh`, every id
 * against at least one independent copy). Without any seed peer a full node has nobody to state-sync from.
 */
data class SuiCluster(
    val env: String,
    val archiveUrl: String,
    val genesisUrl: String,
    val graphQlTipUrl: String,
    val seedPeers: List<SuiSeedPeer> = emptyList(),
)

object SuiClusters
{
    private val MAINNET = SuiCluster(
        env = "mainnet",
        archiveUrl = "https://checkpoints.mainnet.sui.io",
        genesisUrl = "https://github.com/MystenLabs/sui-genesis/raw/main/mainnet/genesis.blob",
        graphQlTipUrl = "https://graphql.mainnet.sui.io/graphql",
        seedPeers = listOf(
            SuiSeedPeer("/dns/mel-00.mainnet.sui.io/udp/8084", "d32b55bdf1737ec415df8c88b3bf91e194b59ee3127e3f38ea46fd88ba2e7849"),
            SuiSeedPeer("/dns/ewr-00.mainnet.sui.io/udp/8084", "c7bf6cb93ca8fdda655c47ebb85ace28e6931464564332bf63e27e90199c50ee"),
            SuiSeedPeer("/dns/ewr-01.mainnet.sui.io/udp/8084", "3227f8a05f0faa1a197c075d31135a366a1c6f3d4872cb8af66c14dea3e0eb66"),
            SuiSeedPeer("/dns/lhr-00.mainnet.sui.io/udp/8084", "c619a5e0f8f36eac45118c1f8bda28f0f508e2839042781f1d4a9818043f732c"),
            SuiSeedPeer("/dns/sui-mainnet-ssfn-1.nodeinfra.com/udp/8084", "0c52ca8d2b9f51be4a50eb44ace863c05aadc940a7bd15d4d3f498deb81d7fc6"),
            SuiSeedPeer("/dns/sui-mainnet-ssfn-2.nodeinfra.com/udp/8084", "1dbc28c105aa7eb9d1d3ac07ae663ea638d91f2b99c076a52bbded296bd3ed5c"),
            SuiSeedPeer("/dns/sui-mainnet-ssfn-ashburn-na.overclock.run/udp/8084", "5ff8461ab527a8f241767b268c7aaf24d0312c7b923913dd3c11ee67ef181e45"),
            SuiSeedPeer("/dns/sui-mainnet-ssfn-dallas-na.overclock.run/udp/8084", "e1a4f40d66f1c89559a195352ba9ff84aec28abab1d3aa1c491901a252acefa6"),
            SuiSeedPeer("/dns/ssn01.mainnet.sui.rpcpool.com/udp/8084", "fadb7ccb0b7fc99223419176e707f5122fef4ea686eb8e80d1778588bf5a0bcd"),
            SuiSeedPeer("/dns/ssn02.mainnet.sui.rpcpool.com/udp/8084", "13783584a90025b87d4604f1991252221e5fd88cab40001642f4b00111ae9b7e"),
        ),
    )
    private val TESTNET = SuiCluster(
        env = "testnet",
        archiveUrl = "https://checkpoints.testnet.sui.io",
        genesisUrl = "https://github.com/MystenLabs/sui-genesis/raw/main/testnet/genesis.blob",
        graphQlTipUrl = "https://graphql.testnet.sui.io/graphql",
        seedPeers = listOf(
            SuiSeedPeer("/dns/yto-tnt-ssfn-01.testnet.sui.io/udp/8084", "2ed53564d5581ded9b6773970ac2f1c84d39f9edf01308ff5a1ffe09b1add7b3"),
            SuiSeedPeer("/dns/yto-tnt-ssfn-00.testnet.sui.io/udp/8084", "6563732e5ab33b4ae09c73a98fd37499b71b8f03c27b5cc51acc26934974aff2"),
            SuiSeedPeer("/dns/nrt-tnt-ssfn-00.testnet.sui.io/udp/8084", "23a1f7cd901b6277cbedaa986b3fc183f171d800cabba863d48f698f518967e1"),
            SuiSeedPeer("/dns/ewr-tnt-ssfn-00.testnet.sui.io/udp/8084", "df8a8d128051c249e224f95fcc463f518a0ebed8986bbdcc11ed751181fecd38"),
            SuiSeedPeer("/dns/lax-tnt-ssfn-00.testnet.sui.io/udp/8084", "f9a72a0a6c17eed09c27898eab389add704777c03e135846da2428f516a0c11d"),
            SuiSeedPeer("/dns/lhr-tnt-ssfn-00.testnet.sui.io/udp/8084", "9393d6056bb9c9d8475a3cf3525c747257f17c6a698a7062cbbd1875bc6ef71e"),
            SuiSeedPeer("/dns/mel-tnt-ssfn-00.testnet.sui.io/udp/8084", "c88742f46e66a11cb8c84aca488065661401ef66f726cb9afeb8a5786d83456e"),
        ),
    )

    fun normalizeEnv(raw: String): String
    {
        val e = raw.trim().lowercase()
        return if (e == "testnet") "testnet" else "mainnet"
    }

    fun lookup(raw: String): SuiCluster =
        if (normalizeEnv(raw) == "testnet") TESTNET else MAINNET
}
