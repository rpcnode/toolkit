package rpcnode.toolkit.chains.base.infrastructure

import kotlin.test.Test
import kotlin.test.assertTrue

class BaseUnitBodiesTest
{
    @Test
    fun reth_unit_includes_chain_and_ports()
    {
        val body = BaseUnitBodies.reth(
            env = "mainnet",
            bin = "/opt/base/mainnet/bin/base-reth-node",
            datadir = "/data/rpcnode/base/mainnet/execution",
            jwtPath = "/etc/base/mainnet/jwt.hex",
            rpcPort = 8571,
            wsPort = 8581,
            enginePort = 8572,
            p2pPort = 30353,
            discoveryV5 = 9203,
            cluster = BaseClusters.lookup("mainnet"),
            logFile = "/data/rpcnode/base/mainnet/execution/logs/node.out",
        )
        assertTrue(body.contains("--chain=base"))
        assertTrue(body.contains("--http.port=8571"))
        assertTrue(body.contains("--authrpc.port=8572"))
        assertTrue(body.contains("--discovery.v5.port=9203"))
        assertTrue(body.contains("WantedBy=multi-user.target"))
    }

    @Test
    fun consensus_unit_requires_reth()
    {
        val body = BaseUnitBodies.consensus(
            env = "mainnet",
            wrapper = "/opt/base/mainnet/bin/run-base-consensus.sh",
            etc = "/etc/base/mainnet",
            rethUnit = "rpcnode-base-mainnet.service",
            logFile = "/data/rpcnode/base/mainnet/execution/logs/consensus.out",
        )
        assertTrue(body.contains("Requires=rpcnode-base-mainnet.service"))
        assertTrue(body.contains("EnvironmentFile=-/etc/base/mainnet/consensus.env"))
        assertTrue(body.contains("ExecStart=/opt/base/mainnet/bin/run-base-consensus.sh"))
    }
}
