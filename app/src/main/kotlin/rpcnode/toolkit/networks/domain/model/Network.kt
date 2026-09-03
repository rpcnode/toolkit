package rpcnode.toolkit.networks.domain.model

import rpcnode.toolkit.catalog.domain.NetworkId

/** A network the operator turned on (or skipped/parked) for this panel install. */
data class Network(
    val network: NetworkId,
    val status: NetworkStatus,
    val addedAt: String,
    val notes: String,
)
