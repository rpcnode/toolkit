package rpcnode.toolkit.clients.infrastructure.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.model.ClientArtifactRole
import rpcnode.toolkit.clients.domain.model.PortConfigPolicy
import rpcnode.toolkit.clients.domain.model.ClientVersionSource

class YamlClientProgramCatalogTest
{
    @Test
    fun loads_bitcoin_programs_with_a_live_github_source_for_every_env()
    {
        val catalog = YamlClientProgramCatalog()
        val mainnet = catalog.programsFor(NetworkId.BITCOIN, EnvId.MAINNET)
        assertEquals(1, mainnet.size)
        val program = mainnet.single()
        assertEquals("bitcoin", program.programId)
        val source = assertIs<ClientVersionSource.GitHubRelease>(program.source)
        assertEquals("bitcoin/bitcoin", source.repo)
        assertEquals("bitcoin-x86_64-linux-gnu.tar.gz", program.artifacts.single().name)
        assertEquals("bitcoin-aarch64-linux-gnu.tar.gz", program.artifacts.single().nameAarch64)
        assertTrue(program.artifacts.single().urlTemplate.contains("{version}"))
        assertEquals(1, program.configs.size)
        assertTrue(program.configs.single().urlTemplate.contains("{tag}"))

        for (env in listOf(EnvId.TESTNET4, EnvId.SIGNET, EnvId.REGTEST))
        {
            assertEquals(1, catalog.programsFor(NetworkId.BITCOIN, env).size)
        }
    }

    @Test
    fun bitcoin_ports_are_fixed_and_differ_per_env()
    {
        val catalog = YamlClientProgramCatalog()

        val mainnet = catalog.programsFor(NetworkId.BITCOIN, EnvId.MAINNET).single()
        assertEquals(
            setOf("p2p" to 8333, "rpc" to 8332, "zmq_rawblock" to 28332, "zmq_rawtx" to 28333),
            mainnet.ports.map { it.role to it.port }.toSet(),
        )

        val regtest = catalog.programsFor(NetworkId.BITCOIN, EnvId.REGTEST).single()
        assertEquals(
            setOf("p2p" to 18444, "rpc" to 18443, "zmq_rawblock" to 28362, "zmq_rawtx" to 28363),
            regtest.ports.map { it.role to it.port }.toSet(),
        )

        val allPorts = listOf(EnvId.MAINNET, EnvId.TESTNET4, EnvId.SIGNET, EnvId.REGTEST)
            .flatMap { catalog.programsFor(NetworkId.BITCOIN, it).single().ports.map { p -> p.port } }
        assertEquals(allPorts.size, allPorts.toSet().size, "no port shared across bitcoin envs")
    }

    @Test
    fun loads_tron_programs_with_pinned_sources_per_env()
    {
        val catalog = YamlClientProgramCatalog()
        val mainnet = catalog.programsFor(NetworkId.TRON, EnvId.MAINNET).single()
        val mainnetSource = assertIs<ClientVersionSource.Pinned>(mainnet.source)
        assertEquals("4.8.2.1", mainnetSource.version)
        assertEquals("GreatVoyage-v4.8.2.1", mainnetSource.tag)

        val nile = catalog.programsFor(NetworkId.TRON, EnvId.NILE).single()
        val nileSource = assertIs<ClientVersionSource.Pinned>(nile.source)
        assertEquals("4.8.2.1-PQ1-build1", nileSource.version)
        assertEquals(8, nile.requirements.javaMajor)
        assertEquals("logs/tron.log", nile.requirements.logFile)

        val shasta = catalog.programsFor(NetworkId.TRON, EnvId.SHASTA).single()
        assertEquals(1, shasta.configs.size)
        assertEquals(ClientArtifactRole.CONFIG, shasta.configs.single().role)
        assertTrue(shasta.configs.single().optional)
        assertEquals(8, shasta.requirements.javaMajor)
        assertEquals(8, mainnet.requirements.javaMajor)
        assertEquals("logs/tron.log", mainnet.requirements.logFile)
    }

    @Test
    fun tron_ports_are_fixed_and_differ_per_env()
    {
        val catalog = YamlClientProgramCatalog()

        val mainnet = catalog.programsFor(NetworkId.TRON, EnvId.MAINNET).single()
        assertEquals(
            setOf(
                "p2p" to 18888,
                "http_fullnode" to 18090,
                "http_solidity" to 18190,
                "http_pbft" to 18191,
                "grpc_fullnode" to 50051,
                "grpc_solidity" to 50061,
                "grpc_pbft" to 50071,
                "metrics" to 9527,
            ),
            mainnet.ports.map { it.role to it.port }.toSet(),
        )

        val allPorts = listOf(EnvId.MAINNET, EnvId.NILE, EnvId.SHASTA)
            .flatMap { catalog.programsFor(NetworkId.TRON, it).single().ports.map { p -> p.port } }
        assertEquals(allPorts.size, allPorts.toSet().size, "no port shared across tron envs")
    }

    @Test
    fun bitcoin_port_config_policies_are_parsed_from_yaml()
    {
        val catalog = YamlClientProgramCatalog()
        val mainnet = catalog.programsFor(NetworkId.BITCOIN, EnvId.MAINNET).single()
        val byRole = mainnet.ports.associateBy { it.role }
        assertEquals(PortConfigPolicy.REQUIRED, byRole["p2p"]?.configPolicy)
        assertEquals(PortConfigPolicy.REQUIRED, byRole["rpc"]?.configPolicy)
        assertEquals(PortConfigPolicy.OPTIONAL, byRole["zmq_rawblock"]?.configPolicy)
        assertEquals(PortConfigPolicy.OPTIONAL, byRole["zmq_rawtx"]?.configPolicy)

        val tron = catalog.programsFor(NetworkId.TRON, EnvId.MAINNET).single()
        val tronByRole = tron.ports.associateBy { it.role }
        assertEquals(PortConfigPolicy.REQUIRED, tronByRole["p2p"]?.configPolicy)
        assertEquals(PortConfigPolicy.NONE, tronByRole["grpc_solidity"]?.configPolicy)
        assertEquals(PortConfigPolicy.NONE, tronByRole["metrics"]?.configPolicy)
    }

    @Test
    fun unknown_network_has_no_programs()
    {
        val catalog = YamlClientProgramCatalog()
        val unknown = NetworkId.parse("does-not-exist")!!
        assertTrue(catalog.programsFor(unknown, EnvId.MAINNET).isEmpty())
    }
}
