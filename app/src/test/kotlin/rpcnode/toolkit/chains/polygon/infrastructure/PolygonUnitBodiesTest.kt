package rpcnode.toolkit.chains.polygon.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolygonUnitBodiesTest
{
    @Test
    fun bor_unit_requires_heimdall()
    {
        val body = PolygonUnitBodies.bor(
            env = "mainnet",
            bin = "/opt/polygon/bin/bor",
            configPath = "/data/rpcnode/polygon/mainnet/bor/datadir/config.toml",
            heimdallUnit = "rpcnode-polygon-heimdall-mainnet.service",
            logFile = "/data/rpcnode/polygon/mainnet/bor/logs/node.out",
        )
        assertTrue(body.contains("Requires=rpcnode-polygon-heimdall-mainnet.service"))
        assertTrue(body.contains("bor server -config"))
        assertTrue(body.contains("/opt/polygon/bin/bor"))
    }

    @Test
    fun heimdall_unit_uses_home()
    {
        val body = PolygonUnitBodies.heimdall(
            env = "amoy",
            bin = "/opt/polygon/bin/heimdalld",
            home = "/data/rpcnode/polygon/amoy/heimdall",
            logFile = "/data/rpcnode/polygon/amoy/bor/logs/heimdall.out",
        )
        assertTrue(body.contains("heimdalld start --home"))
        assertTrue(body.contains("--chain=amoy"))
        assertTrue(body.contains("--rest-server"))
        assertTrue(body.contains("/data/rpcnode/polygon/amoy/heimdall"))
        assertEquals("rpcnode-polygon-heimdall-amoy.service", PolygonUnitBodies.heimdallUnitName("amoy"))
    }
}
