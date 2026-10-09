package rpcnode.toolkit.chains.arb.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class ArbNodeStartTest
{
    @Test
    fun plan_encodes_env_and_disk_roles()
    {
        val plan = ArbNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.ARB,
                env = "mainnet",
                program = "nitro",
                configFile = null,
                nodeDir = "/data/rpcnode/arbitrum/mainnet/execution",
                diskLayoutJson = """
                  {"roles":[
                      {"id":"execution","dir":"/data/rpcnode/arbitrum/mainnet/execution"},
                      {"id":"snapshots","dir":"/data/rpcnode/arbitrum/mainnet/snapshots"}
                  ]}
                """.trimIndent(),
                installOptionsJson = """{"snapshot":"archive"}""",
            ),
        )
        assertEquals("binary", plan.launch.kind)
        assertEquals("nitro", plan.launch.entry)
        assertEquals("eth_rpc", plan.height.kind)
        assertEquals("http", plan.height.portRole)
        assertTrue(plan.launch.args.contains("--toolkit-env=mainnet"))
        assertTrue(plan.launch.args.contains("--toolkit-snapshot=archive"))
        assertTrue(plan.launch.args.contains("--toolkit-chain-id=42161"))
        assertTrue(plan.launch.args.contains("--toolkit-execution=/data/rpcnode/arbitrum/mainnet/execution"))
        assertTrue(plan.launch.args.contains("--toolkit-snapshots=/data/rpcnode/arbitrum/mainnet/snapshots"))
        assertTrue(
            plan.launch.args.contains("--toolkit-l1-rpc=https://ethereum-rpc.publicnode.com"),
        )
        assertTrue(
            plan.launch.args.contains("--toolkit-l1-beacon=https://ethereum-beacon-api.publicnode.com"),
        )
    }

    @Test
    fun plan_defaults_snapshot_to_pruned()
    {
        val plan = ArbNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.ARB,
                env = "sepolia",
                program = "nitro",
                configFile = null,
            ),
        )
        assertTrue(plan.launch.args.contains("--toolkit-env=sepolia"))
        assertTrue(plan.launch.args.contains("--toolkit-snapshot=pruned"))
        assertTrue(plan.launch.args.contains("--toolkit-chain-id=421614"))
        assertTrue(
            plan.launch.args.contains(
                "--toolkit-l1-rpc=https://ethereum-sepolia-rpc.publicnode.com",
            ),
        )
    }
}
