package rpcnode.toolkit.clients.application.validate

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import rpcnode.toolkit.clients.domain.model.PortProtocol
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository

/**
 * Rule for adding a network (docs/adding-a-network-intake.md): its ports in chains/<id>/clients.yml must be free
 * across every shipped network/env — several node types share one host. Fails the build with the clashing ports.
 */
class ShippedPortCatalogTest
{
    @Test
    fun every_network_env_has_its_own_ports()
    {
        val catalog = YamlClientProgramCatalog()
        val facts = YamlNetworkFactsRepository()
        val programs = catalog.all()
        assertTrue(programs.isNotEmpty(), "catalog loaded no programs")
        val conflicts = DetectPortConflictsUseCase()(programs) { facts.factsFor(it)?.oneEnvPerHost == true }
        if (conflicts.isNotEmpty())
        {
            fail(
                "Port clash in chains/*/clients.yml — pick a free port (see the list in the build output):\n" +
                    conflicts.joinToString("\n") { c ->
                        "  ${c.port}: " + c.usedBy.joinToString(", ") { "${it.network.value}/${it.env.value} ${it.role}" }
                    },
            )
        }
    }

    @Test
    fun ton_p2p_is_declared_udp()
    {
        val ports = YamlClientProgramCatalog().all()
            .filter { it.network.value == "ton" }
            .flatMap { it.ports }
            .filter { it.role == "p2p" }
        assertTrue(ports.isNotEmpty(), "ton p2p ports missing")
        assertTrue(ports.all { it.protocol == PortProtocol.UDP }, "ton p2p must be protocol: udp")
    }
}
