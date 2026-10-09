package rpcnode.toolkit.chains.ethereum.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EthereumUnitBodiesTest
{
    @Test
    fun geth_unit_contains_snap_and_ports()
    {
        val body = EthereumUnitBodies.geth(
            env = "mainnet",
            bin = "/opt/ethereum/bin/geth",
            datadir = "/data/rpcnode/ethereum/mainnet/geth",
            jwtPath = "/etc/ethereum/mainnet/jwt.hex",
            rpcPort = 8545,
            p2pPort = 30303,
            enginePort = 8551,
            cluster = EthereumClusters.lookup("mainnet"),
            archive = false,
            logFile = "/data/rpcnode/ethereum/mainnet/geth/logs/node.out",
        )
        assertTrue(body.contains("--syncmode snap"))
        assertTrue(body.contains("--gcmode full"))
        assertTrue(body.contains("--http.port 8545"))
        assertTrue(body.contains("--history.chain postmerge"))
        assertTrue(body.contains("WantedBy=multi-user.target"))
        assertTrue(!body.contains("User=nodeop"))
    }

    @Test
    fun lighthouse_unit_after_geth()
    {
        val body = EthereumUnitBodies.lighthouse(
            env = "sepolia",
            bin = "/usr/local/bin/lighthouse",
            datadir = "/data/rpcnode/ethereum/sepolia/lighthouse",
            jwtPath = "/etc/ethereum/sepolia/jwt.hex",
            enginePort = 8552,
            beaconPort = 5053,
            consensusP2p = 9100,
            cluster = EthereumClusters.lookup("sepolia"),
            gethUnit = "rpcnode-ethereum-sepolia.service",
            logFile = "/tmp/lh.out",
        )
        assertTrue(body.contains("After=network-online.target rpcnode-ethereum-sepolia.service"))
        assertTrue(body.contains("--network sepolia"))
        assertEquals("rpcnode-ethereum-lighthouse-sepolia.service", EthereumUnitBodies.lighthouseUnitName("sepolia"))
    }
}
