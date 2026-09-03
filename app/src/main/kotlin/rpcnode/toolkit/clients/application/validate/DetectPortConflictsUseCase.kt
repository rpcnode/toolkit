package rpcnode.toolkit.clients.application.validate

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec

/** One catalog port and which network/env it is fixed for. */
data class PortConflictUsage(
    val network: NetworkId,
    val env: EnvId,
    val programId: String,
    val role: String,
)

/** A port number the shipped catalog assigns to more than one network/env — they can never
 *  run as separate nodes on the same host. */
data class PortConflict(
    val port: Int,
    val usedBy: List<PortConflictUsage>,
)

/**
 * Every network/env is meant to get its own fixed ports (so several node types can coexist on
 * one host). Two network/env pairs claiming the same port is a catalog authoring mistake — this
 * only inspects the shipped YAML, it never touches a real host.
 */
class DetectPortConflictsUseCase
{
    operator fun invoke(programs: List<ClientProgramSpec>): List<PortConflict>
    {
        return programs
            .flatMap { spec -> spec.ports.map { it.port to PortConflictUsage(spec.network, spec.env, spec.programId, it.role) } }
            .groupBy({ it.first }, { it.second })
            .filterValues { usages -> usages.map { it.network to it.env }.distinct().size > 1 }
            .map { (port, usages) -> PortConflict(port, usages) }
            .sortedBy { it.port }
    }
}
