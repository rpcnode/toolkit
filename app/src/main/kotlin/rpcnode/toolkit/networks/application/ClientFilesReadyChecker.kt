package rpcnode.toolkit.networks.application

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId

/** Is a client artifact for [network] present on disk for any of [envs]? */
fun interface ClientFilesReadyChecker
{
    fun ready(network: NetworkId, envs: List<EnvId>): Boolean
}
