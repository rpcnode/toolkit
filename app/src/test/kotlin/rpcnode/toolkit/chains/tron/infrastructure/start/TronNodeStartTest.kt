package rpcnode.toolkit.chains.tron.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class TronNodeStartTest
{
    @Test
    fun plan_java_jar_and_http_height()
    {
        val plan = TronNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.TRON,
                env = "nile",
                program = "FullNode.jar",
                configFile = "config-nile.conf",
                javaMajor = 8,
                logFile = "logs/tron.log",
            ),
        )
        assertEquals("java_jar", plan.launch.kind)
        assertEquals("FullNode.jar", plan.launch.entry)
        assertEquals(listOf("-c", "config-nile.conf", "-d", "."), plan.launch.args)
        assertEquals(8, plan.launch.javaMajor)
        assertEquals("logs/tron.log", plan.launch.logFile)
        assertEquals("tron_http", plan.height.kind)
        assertEquals("http_fullnode", plan.height.portRole)
    }

    @Test
    fun plan_passes_through_null_java_major_from_catalog()
    {
        val plan = TronNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.TRON,
                env = "nile",
                program = "FullNode.jar",
                configFile = "config-nile.conf",
                javaMajor = null,
            ),
        )
        assertEquals(null, plan.launch.javaMajor)
    }
}
