package rpcnode.toolkit.agent.presentation.http

import rpcnode.toolkit.agent.application.reserve.ReserveAgentPortsUseCase
import rpcnode.toolkit.agent.application.reserve.ReservedPortsHost
import rpcnode.toolkit.agent.application.reserve.ReservedPortsStatus
import rpcnode.toolkit.agent.domain.model.AGENT_RESERVED_PORTS
import rpcnode.toolkit.agent.infrastructure.catalog.CatalogFixedPortsReader
import rpcnode.toolkit.agent.infrastructure.sysctl.OsReservedPortsHost

/**
 * First thing on process start, before the HTTP bind. Reserves the agent's own API port plus
 * every fixed port the shipped `clients` YAML catalog assigns to a network/env — a node install
 * can only bind them if the kernel never hands them out as an ephemeral source port first.
 * Failure is logged by the caller.
 */
fun reserveAgentPortsOnStart(
    rangeFileEnv: String? = System.getenv("AGENT_RANGE_FILE"),
    confEnv: String? = System.getenv("AGENT_SYSCTL_CONF"),
    host: ReservedPortsHost = OsReservedPortsHost(),
    catalogPorts: CatalogFixedPortsReader = CatalogFixedPortsReader(),
): ReservedPortsStatus
{
    val ours = (AGENT_RESERVED_PORTS + catalogPorts.read()).distinct().sorted()
    return ReserveAgentPortsUseCase(
        host = host,
        ours = ours,
        confPath = confEnv?.trim()?.ifEmpty { null } ?: ReserveAgentPortsUseCase.DEFAULT_CONF_PATH,
        rangeFile = rangeFileEnv?.trim()?.ifEmpty { null } ?: ReserveAgentPortsUseCase.DEFAULT_RANGE_FILE,
    )()
}
