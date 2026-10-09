package rpcnode.toolkit.catalog.application

import rpcnode.toolkit.catalog.domain.Env
import rpcnode.toolkit.catalog.domain.NetworkCatalog
import rpcnode.toolkit.catalog.domain.NetworkId

/** Resolve one shipped env. Empty network is not a default chain. */
class LookupNetworkEnvUseCase(
    private val catalog: NetworkCatalog,
)
{
    operator fun invoke(network: String, env: String): Env?
    {
        val id = NetworkId.parse(network) ?: return null
        return catalog.find(id)?.env(env)
    }
}
