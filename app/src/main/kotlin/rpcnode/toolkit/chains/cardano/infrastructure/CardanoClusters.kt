package rpcnode.toolkit.chains.cardano.infrastructure

/** Static facts of one Cardano env: the 1-second-slot clock and the Mithril aggregator. */
data class CardanoCluster(
    val env: String,
    /** Absolute slot of the first 1-second slot. */
    val shelleyStartSlot: Long,
    /** Unix time (s) of [shelleyStartSlot]. */
    val shelleyStartUnix: Long,
    val aggregator: String,
    /** Directory under `mithril-infra/configuration` holding the verification keys. */
    val mithrilKeyDir: String,
)
{
    fun currentSlot(nowUnixSeconds: Long): Long = shelleyStartSlot + (nowUnixSeconds - shelleyStartUnix)

    fun genesisKeyUrl(): String = keyUrl("genesis.vkey")

    fun ancillaryKeyUrl(): String = keyUrl("ancillary.vkey")

    private fun keyUrl(file: String) =
        "https://raw.githubusercontent.com/IntersectMBO/mithril/main/mithril-infra/configuration/$mithrilKeyDir/$file"
}

object CardanoClusters
{
    private val MAINNET = CardanoCluster(
        "mainnet", 4_492_800, 1_596_059_091,
        "https://aggregator.release-mainnet.api.mithril.network/aggregator", "release-mainnet",
    )
    private val PREPROD = CardanoCluster(
        "preprod", 86_400, 1_655_769_600,
        "https://aggregator.release-preprod.api.mithril.network/aggregator", "release-preprod",
    )
    private val PREVIEW = CardanoCluster(
        "preview", 0, 1_666_656_000,
        "https://aggregator.pre-release-preview.api.mithril.network/aggregator", "pre-release-preview",
    )

    fun normalizeEnv(raw: String): String =
        when (raw.trim().lowercase())
        {
            "preprod" -> "preprod"
            "preview" -> "preview"
            else -> "mainnet"
        }

    fun lookup(raw: String): CardanoCluster =
        when (normalizeEnv(raw))
        {
            "preprod" -> PREPROD
            "preview" -> PREVIEW
            else -> MAINNET
        }
}
