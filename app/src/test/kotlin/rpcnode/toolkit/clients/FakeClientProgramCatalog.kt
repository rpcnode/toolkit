package rpcnode.toolkit.clients

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog

class FakeClientProgramCatalog(
    private val programs: List<ClientProgramSpec> = emptyList(),
) : ClientProgramCatalog
{
    override fun programsFor(network: NetworkId, env: EnvId): List<ClientProgramSpec> =
        programs.filter { it.network == network && it.env == env }

    override fun all(): List<ClientProgramSpec> = programs
}
