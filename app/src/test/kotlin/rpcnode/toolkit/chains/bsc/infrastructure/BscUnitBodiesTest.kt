package rpcnode.toolkit.chains.bsc.infrastructure

import kotlin.test.Test
import kotlin.test.assertTrue

class BscUnitBodiesTest
{
    @Test
    fun geth_unit_includes_parlia_and_ports()
    {
        val body = BscUnitBodies.geth(
            env = "mainnet",
            bin = "/opt/bsc/mainnet/bin/geth",
            datadir = "/data/rpcnode/bsc/mainnet/chaindata",
            configPath = "/etc/bsc/mainnet/config.toml",
            rpcPort = 8575,
            p2pPort = 30311,
            cacheMb = 8192,
            logFile = "/data/rpcnode/bsc/mainnet/chaindata/logs/node.out",
        )
        assertTrue(body.contains("parlia"))
        assertTrue(body.contains("--http.port 8575"))
        assertTrue(body.contains("--port 30311"))
        assertTrue(body.contains("--config /etc/bsc/mainnet/config.toml"))
        assertTrue(body.contains("WantedBy=multi-user.target"))
    }
}
