package rpcnode.toolkit.catalog.domain

data class Env(
    val id: EnvId,
    val displayName: String,
    /** Overrides the data dir basename (ltc testnet → testnet4). */
    val dataLeaf: String = "",
)
