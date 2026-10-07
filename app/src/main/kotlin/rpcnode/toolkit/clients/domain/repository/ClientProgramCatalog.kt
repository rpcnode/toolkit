package rpcnode.toolkit.clients.domain.repository

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec

/** Shipped catalog of downloadable programs. Empty result = this network/env has no CDN client. */
interface ClientProgramCatalog
{
    fun programsFor(network: NetworkId, env: EnvId): List<ClientProgramSpec>

    /** Every program this install knows about (used to complete "missing" sibling rows). */
    fun all(): List<ClientProgramSpec>
}
