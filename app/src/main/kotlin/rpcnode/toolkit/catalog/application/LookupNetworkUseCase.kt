package rpcnode.toolkit.catalog.application

import rpcnode.toolkit.catalog.domain.Chain
import rpcnode.toolkit.catalog.domain.NetworkCatalog
import rpcnode.toolkit.catalog.domain.NetworkId

/** Resolve a shipped chain by network id. Empty or a port number is not a chain. */
class LookupNetworkUseCase(
    private val catalog: NetworkCatalog,
)
{
    operator fun invoke(raw: String): Chain?
    {
        val id = NetworkId.parse(raw) ?: return null
        return catalog.find(id)
    }
}
