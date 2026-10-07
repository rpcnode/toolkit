package rpcnode.toolkit.clients.application

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository

/** Shared by probe-all/sync-all: which catalog programs does a `{network?, env?, program?}` request touch? */
internal suspend fun resolveClientTargets(
    versionRepository: ClientVersionRepository,
    programCatalog: ClientProgramCatalog,
    network: String?,
    env: String?,
    program: String?,
): List<ClientProgramSpec>
{
    val networkRaw = network?.trim()?.lowercase()?.ifEmpty { null }
    val candidates = if (networkRaw == null)
    {
        val tracked = versionRepository.list().filter { it.currentVersion.isNotBlank() }
            .map { it.network to it.env }.distinct()
        val purgedNetworks = tracked.map { it.first }.distinct().filter { versionRepository.isPurged(it) }.toSet()
        tracked.filter { it.first !in purgedNetworks }.flatMap { (net, e) -> programCatalog.programsFor(net, e) }
    }
    else
    {
        val networkId = NetworkId.parse(networkRaw) ?: return emptyList()
        val envId = env?.trim()?.ifEmpty { null }?.let { EnvId.parse(it) }
        if (envId != null)
        {
            programCatalog.programsFor(networkId, envId)
        }
        else
        {
            programCatalog.all().filter { it.network == networkId }
        }
    }
    val programRaw = program?.trim()?.lowercase()?.ifEmpty { null }
    return if (programRaw == null) candidates else candidates.filter { it.programId.lowercase() == programRaw }
}
