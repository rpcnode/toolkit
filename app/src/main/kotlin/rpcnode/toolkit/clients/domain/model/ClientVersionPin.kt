package rpcnode.toolkit.clients.domain.model

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId

/**
 * One row of `client_versions` — current + latest known version for one program on one
 * network/env. A pin only exists here after the first successful download (see
 * [rpcnode.toolkit.clients.domain.repository.ClientVersionRepository.applySynced]); probing alone
 * never creates a row.
 */
data class ClientVersionPin(
    val network: NetworkId,
    val env: EnvId,
    val program: String,
    val currentVersion: String = "",
    val currentTag: String = "",
    val latestVersion: String = "",
    val latestTag: String = "",
    val source: String = "",
    val url: String = "",
    val notes: String = "",
    val skipReason: String = "",
    val probeError: String = "",
    val probedAt: String = "",
    val updatedAt: String = "",
)
{
    val status: ClientStatus
        get() = ClientStatus.compute(currentVersion, latestVersion, skipReason, probeError)
}
