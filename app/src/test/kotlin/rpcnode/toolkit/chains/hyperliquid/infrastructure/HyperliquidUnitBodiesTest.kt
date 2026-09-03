package rpcnode.toolkit.chains.hyperliquid.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HyperliquidUnitBodiesTest
{
    @Test
    fun unit_renders_home_workdir_and_nofile()
    {
        val body = HyperliquidUnitBodies.unit(
            env = "mainnet",
            nodeDir = "/data/rpcnode/hyperliquid/mainnet/chain",
            workdir = "/data/rpcnode/hyperliquid/mainnet/chain/hl",
            execStart = HyperliquidUnitBodies.execStart("/data/rpcnode/hyperliquid/mainnet/chain/bin/hl-visor"),
            logFile = "/data/rpcnode/hyperliquid/mainnet/chain/logs/hl-visor.log",
        )
        assertTrue(body.contains("Environment=HOME=/data/rpcnode/hyperliquid/mainnet/chain"))
        assertTrue(body.contains("WorkingDirectory=/data/rpcnode/hyperliquid/mainnet/chain/hl"))
        assertTrue(body.contains("LimitNOFILE=${HyperliquidUnitBodies.LIMIT_NOFILE}"))
        assertTrue(body.contains("run-non-validator --replica-cmds-style actions --serve-eth-rpc --serve-info"))
    }

    @Test
    fun gossip_json_lists_peers()
    {
        val cluster = HyperliquidClusters.lookup("mainnet")
        val json = HyperliquidUnitBodies.gossipJson(cluster, listOf("1.2.3.4", "5.6.7.8"))
        assertTrue(json.contains("\"Ip\": \"1.2.3.4\""))
        assertTrue(json.contains("\"Ip\": \"5.6.7.8\""))
        assertTrue(json.contains("\"chain\": \"Mainnet\""))
        assertTrue(json.contains("\"try_new_peers\": true"))
    }

    @Test
    fun visor_json_sets_chain()
    {
        assertEquals(
            """{"chain": "Testnet"}""" + "\n",
            HyperliquidUnitBodies.visorJson(HyperliquidClusters.lookup("testnet")),
        )
    }
}
