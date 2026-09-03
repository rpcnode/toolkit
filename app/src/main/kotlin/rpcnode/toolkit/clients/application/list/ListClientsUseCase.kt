package rpcnode.toolkit.clients.application.list

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.model.ClientStatus
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository

data class ClientsStats(
    val total: Int,
    val stale: Int,
    val fail: Int,
    val missing: Int,
    val deleted: Int = 0,
)

data class ListClientsResult(
    val rows: List<ClientVersionPin>,
    val stats: ClientsStats,
    val probedAt: String?,
)

/**
 * Only networks/envs that have been synced at least once show up here — "Check latest" alone
 * does not add a network. Sibling programs on an already-tracked network/env that were never
 * downloaded show up as `missing`.
 */
class ListClientsUseCase(
    private val versionRepository: ClientVersionRepository,
    private val programCatalog: ClientProgramCatalog,
)
{
    suspend operator fun invoke(): ListClientsResult
    {
        val downloaded = versionRepository.list().filter { it.currentVersion.isNotBlank() }
        val trackedNetworkEnvs = downloaded.map { it.network to it.env }.distinct()
            .sortedBy { "${it.first.value}/${it.second.value}" }
        val byKey = downloaded.associateBy { Triple(it.network, it.env, it.program) }
        val emitted = mutableSetOf<Triple<NetworkId, EnvId, String>>()
        val rows = mutableListOf<ClientVersionPin>()

        for ((network, env) in trackedNetworkEnvs)
        {
            for (program in programCatalog.programsFor(network, env))
            {
                val key = Triple(network, env, program.programId)
                rows += byKey[key] ?: ClientVersionPin(network = network, env = env, program = program.programId)
                emitted += key
            }
        }

        for (pin in downloaded)
        {
            val key = Triple(pin.network, pin.env, pin.program)
            if (key in emitted) continue
            val want = programCatalog.programsFor(pin.network, pin.env)
            if (want.isNotEmpty() && want.none { it.programId == pin.program }) continue
            rows += pin
            emitted += key
        }

        val stats = ClientsStats(
            total = rows.size,
            stale = rows.count { it.status == ClientStatus.STALE },
            fail = rows.count { it.status == ClientStatus.FAIL },
            missing = rows.count { it.status == ClientStatus.MISSING },
        )
        val probedAt = rows.mapNotNull { it.probedAt.ifBlank { null } }.maxOrNull()
        return ListClientsResult(rows = rows, stats = stats, probedAt = probedAt)
    }
}
