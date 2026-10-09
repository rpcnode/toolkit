package rpcnode.toolkit.agent.infrastructure.node

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rpcnode.toolkit.agent.application.node.RunningNodeRegistry
import rpcnode.toolkit.agent.domain.model.RunningNode

class HostLocalNodesInventoryTest
{
    @Test
    fun parse_unit_name_takes_last_hyphen_as_env()
    {
        assertEquals("dash" to "mainnet", HostLocalNodesInventory.parseRpcnodeUnitName("rpcnode-dash-mainnet.service"))
        assertEquals("foo-bar" to "testnet", HostLocalNodesInventory.parseRpcnodeUnitName("rpcnode-foo-bar-testnet.service"))
        assertNull(HostLocalNodesInventory.parseRpcnodeUnitName("rpcnode-agent.service"))
        assertNull(HostLocalNodesInventory.parseRpcnodeUnitName("other.service"))
    }

    @Test
    fun inventory_merges_registry_systemd_and_go_markers()
    {
        val root = Files.createTempDirectory("local-nodes-inv")
        val systemd = root.resolve("systemd")
        val goNodes = root.resolve("rpcnode-nodes")
        val etc = root.resolve("etc")
        Files.createDirectories(systemd)
        Files.createDirectories(goNodes)
        Files.createDirectories(etc.resolve("ltc").resolve("mainnet"))
        Files.writeString(systemd.resolve("rpcnode-dash-mainnet.service"), "[Unit]\n")
        Files.writeString(
            goNodes.resolve("bitcoin-mainnet.json"),
            """{"network":"bitcoin","env":"mainnet","public_port":8332}""",
        )
        Files.writeString(
            etc.resolve("ltc").resolve("mainnet").resolve("toolkit.env"),
            "RPCNODE_PUBLIC_PORT=9332\nRPCNODE_NODE_HTTP_PORT=9333\n",
        )
        val registry = object : RunningNodeRegistry
        {
            override fun upsert(node: RunningNode) {}
            override fun remove(nodeId: String) {}
            override fun get(nodeId: String): RunningNode? = null
            override fun list(): List<RunningNode> = listOf(
                RunningNode(
                    nodeId = "n1",
                    network = "tron",
                    env = "nile",
                    nodeDir = "/data/rpcnode/tron/nile/fullnode",
                    httpPort = 8090,
                    pid = 42,
                ),
            )
        }
        val items = HostLocalNodesInventory(
            registry = registry,
            systemdDir = systemd,
            goNodesDir = goNodes,
            etcRoot = etc,
        )()
        val byKey = items.associateBy { "${it.network}/${it.env}" }
        assertTrue(byKey.containsKey("tron/nile"))
        assertEquals("running", byKey.getValue("tron/nile").status)
        assertEquals(8090, byKey.getValue("tron/nile").nodeHttpPort)
        assertTrue(byKey.containsKey("dash/mainnet"))
        assertTrue(byKey.containsKey("bitcoin/mainnet"))
        assertEquals(8332, byKey.getValue("bitcoin/mainnet").publicPort)
        assertTrue(byKey.containsKey("ltc/mainnet"))
        assertEquals(9332, byKey.getValue("ltc/mainnet").publicPort)
        assertEquals(9333, byKey.getValue("ltc/mainnet").nodeHttpPort)
    }
}
