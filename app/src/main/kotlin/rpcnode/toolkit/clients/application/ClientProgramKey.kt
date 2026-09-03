package rpcnode.toolkit.clients.application

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId

data class ClientProgramKey(
    val network: NetworkId,
    val env: EnvId,
    val program: String,
)
