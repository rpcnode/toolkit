package rpcnode.toolkit.nodes.infrastructure.host

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class OwnedUnitsTest
{
    @Test
    fun finds_the_bootstrap_and_indexer_units_of_this_network_and_env_only()
    {
        val system = Files.createTempDirectory("systemd")
        val nodeDir = Files.createTempDirectory("node")
        try
        {
            for (name in listOf(
                "rpcnode-ton-mainnet.service",
                "rpcnode-ton-mainnet-bootstrap.service",
                "rpcnode-ton-mainnet-indexer.service",
                "rpcnode-ton-testnet-bootstrap.service",
                "rpcnode-ton-mainnet-other.service",
                "rpcnode-bitcoin-mainnet-bootstrap.service",
            ))
            {
                Files.writeString(system.resolve(name), "[Unit]\n")
            }
            assertEquals(
                listOf("rpcnode-ton-mainnet-bootstrap.service", "rpcnode-ton-mainnet-indexer.service"),
                HostNodeLaunchSupport.ownedUnits("ton", "mainnet", nodeDir, system),
            )
            assertEquals(
                listOf("rpcnode-ton-testnet-bootstrap.service"),
                HostNodeLaunchSupport.ownedUnits("ton", "testnet", nodeDir, system),
            )
            assertEquals(emptyList(), HostNodeLaunchSupport.ownedUnits("ethereum", "mainnet", nodeDir, system))
        }
        finally
        {
            system.toFile().deleteRecursively()
            nodeDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun recorded_companions_come_first_without_duplicates()
    {
        val system = Files.createTempDirectory("systemd")
        val nodeDir = Files.createTempDirectory("node")
        try
        {
            Files.createDirectories(nodeDir.resolve(".toolkit"))
            Files.writeString(
                nodeDir.resolve(".toolkit/systemd.companions"),
                "rpcnode-ton-mainnet-indexer.service\nlighthouse-ton-mainnet.service\n",
            )
            Files.writeString(system.resolve("rpcnode-ton-mainnet-indexer.service"), "[Unit]\n")
            Files.writeString(system.resolve("rpcnode-ton-mainnet-bootstrap.service"), "[Unit]\n")
            assertEquals(
                listOf(
                    "rpcnode-ton-mainnet-indexer.service",
                    "lighthouse-ton-mainnet.service",
                    "rpcnode-ton-mainnet-bootstrap.service",
                ),
                HostNodeLaunchSupport.ownedUnits("ton", "mainnet", nodeDir, system),
            )
        }
        finally
        {
            system.toFile().deleteRecursively()
            nodeDir.toFile().deleteRecursively()
        }
    }
}
