package rpcnode.toolkit.chains.base.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class BaseNodeStartTest
{
    @Test
    fun plan_encodes_env_and_disk_roles()
    {
        val plan = BaseNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.BASE,
                env = "mainnet",
                program = "base-reth-node",
                configFile = null,
                nodeDir = "/data/rpcnode/base/mainnet/execution",
                diskLayoutJson = """
                  {"roles":[
                      {"id":"execution","dir":"/data/rpcnode/base/mainnet/execution"},
                      {"id":"snapshots","dir":"/data/rpcnode/base/mainnet/snapshots"}
                  ]}
                """.trimIndent(),
                installOptionsJson = """{"snapshot":"full"}""",
            ),
        )
        assertEquals("binary", plan.launch.kind)
        assertEquals("base-reth-node", plan.launch.entry)
        assertEquals("eth_rpc", plan.height.kind)
        assertEquals("http", plan.height.portRole)
        assertTrue(plan.launch.args.contains("--toolkit-env=mainnet"))
        assertTrue(plan.launch.args.contains("--toolkit-snapshot=full"))
        assertTrue(plan.launch.args.contains("--toolkit-execution=/data/rpcnode/base/mainnet/execution"))
        assertTrue(plan.launch.args.contains("--toolkit-snapshots=/data/rpcnode/base/mainnet/snapshots"))
        assertTrue(
            plan.launch.args.any {
                it.startsWith("--toolkit-l1-rpc=https://ethereum-rpc.publicnode.com")
            },
        )
        assertTrue(
            plan.launch.args.any {
                it.startsWith("--toolkit-l1-beacon=https://ethereum-beacon-api.publicnode.com")
            },
        )
    }

    @Test
    fun plan_defaults_snapshot_to_archive()
    {
        val plan = BaseNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.BASE,
                env = "sepolia",
                program = "base-reth-node",
                configFile = null,
            ),
        )
        assertTrue(plan.launch.args.contains("--toolkit-env=sepolia"))
        assertTrue(plan.launch.args.contains("--toolkit-snapshot=archive"))
        assertTrue(plan.launch.args.contains("--toolkit-chain-id=84532"))
        assertTrue(
            plan.launch.args.contains(
                "--toolkit-l1-rpc=https://ethereum-sepolia-rpc.publicnode.com",
            ),
        )
    }

    @Test
    fun plan_honors_l1_install_options()
    {
        val plan = BaseNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.BASE,
                env = "sepolia",
                program = "base-reth-node",
                configFile = null,
                installOptionsJson = """
                  {"l1_rpc":"http://127.0.0.1:8546","l1_beacon":"http://127.0.0.1:5053"}
                """.trimIndent(),
            ),
        )
        assertTrue(plan.launch.args.contains("--toolkit-l1-rpc=http://127.0.0.1:8546"))
        assertTrue(plan.launch.args.contains("--toolkit-l1-beacon=http://127.0.0.1:5053"))
    }
}
