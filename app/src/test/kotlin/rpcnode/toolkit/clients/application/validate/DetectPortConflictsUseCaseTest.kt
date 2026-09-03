package rpcnode.toolkit.clients.application.validate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.model.ClientVersionSource
import rpcnode.toolkit.clients.domain.model.ProgramPort

private fun spec(network: NetworkId, env: EnvId, ports: List<ProgramPort>) = ClientProgramSpec(
    network = network,
    env = env,
    programId = network.value,
    source = ClientVersionSource.Pinned(version = "1", tag = "v1", label = "test"),
    ports = ports,
)

class DetectPortConflictsUseCaseTest
{
    @Test
    fun no_conflicts_when_every_network_env_has_disjoint_ports()
    {
        val detect = DetectPortConflictsUseCase()
        val programs = listOf(
            spec(NetworkId.BITCOIN, EnvId.MAINNET, listOf(ProgramPort("p2p", 8333))),
            spec(NetworkId.BITCOIN, EnvId.REGTEST, listOf(ProgramPort("p2p", 18444))),
            spec(NetworkId.TRON, EnvId.MAINNET, listOf(ProgramPort("p2p", 18888))),
        )

        assertTrue(detect(programs).isEmpty())
    }

    @Test
    fun flags_the_same_port_claimed_by_two_network_env_pairs()
    {
        val detect = DetectPortConflictsUseCase()
        val programs = listOf(
            spec(NetworkId.BITCOIN, EnvId.MAINNET, listOf(ProgramPort("p2p", 18888))),
            spec(NetworkId.TRON, EnvId.MAINNET, listOf(ProgramPort("p2p", 18888))),
        )

        val conflicts = detect(programs)

        assertEquals(1, conflicts.size)
        assertEquals(18888, conflicts.single().port)
        assertEquals(
            setOf(NetworkId.BITCOIN to EnvId.MAINNET, NetworkId.TRON to EnvId.MAINNET),
            conflicts.single().usedBy.map { it.network to it.env }.toSet(),
        )
    }

    @Test
    fun does_not_flag_one_env_reusing_its_own_port_across_two_roles()
    {
        val detect = DetectPortConflictsUseCase()
        val programs = listOf(
            spec(
                NetworkId.BITCOIN,
                EnvId.MAINNET,
                listOf(ProgramPort("p2p", 8333), ProgramPort("rpc", 8332)),
            ),
        )

        assertTrue(detect(programs).isEmpty())
    }
}
