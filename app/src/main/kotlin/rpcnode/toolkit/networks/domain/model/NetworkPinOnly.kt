package rpcnode.toolkit.networks.domain.model

import rpcnode.toolkit.catalog.domain.NetworkId

/**
 * Host pin — installed on the node with no CDN tarball under `clients/<id>/`.
 * Use only when there is no public downloadable binary. Container images alone
 * are not a reason: prefer GitHub/CDN artifacts (see adding-a-network playbook).
 * Static today; harmless while these ids are absent from the catalog — starts applying
 * automatically once one of them is registered.
 */
object NetworkPinOnly
{
    private val ids = setOf("ton", "robinhood", "optimism")

    fun isPinOnly(id: NetworkId): Boolean = id.value in ids
}
