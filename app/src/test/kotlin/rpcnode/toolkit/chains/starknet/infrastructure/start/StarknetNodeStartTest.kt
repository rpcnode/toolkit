package rpcnode.toolkit.chains.starknet.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.starknet.infrastructure.StarknetChain
import rpcnode.toolkit.chains.starknet.infrastructure.StarknetOptions
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class StarknetNodeStartTest
{
    private fun ctx(env: String, options: String = "", layout: String = "") = ChainNodeStartContext(
        network = NetworkId.STARKNET,
        env = env,
        program = "juno",
        configFile = null,
        nodeDir = "/data/starknet/$env",
        installOptionsJson = options,
        diskLayoutJson = layout,
    )

    @Test
    fun pruned_snapshot_runs_pruned_with_the_upstream_default_window_and_the_public_l1_of_the_env()
    {
        val plan = StarknetNodeStart().plan(ctx("sepolia", """{"snapshot":"pruned"}"""))
        assertEquals(NetworkId.STARKNET, StarknetNodeStart().networkId)
        assertEquals("binary", plan.launch.kind)
        assertEquals("bin/juno", plan.launch.entry)
        assertEquals("juno-*.zip", plan.launch.extractArchiveGlob)
        assertEquals("starknet_rpc", plan.height.kind)
        assertEquals("http", plan.height.portRole)
        assertTrue(plan.launch.args.contains("--toolkit-env=sepolia"))
        assertTrue(plan.launch.args.contains("--toolkit-snapshot=pruned"))
        assertTrue(plan.launch.args.contains("--toolkit-prune=100000"))
        assertTrue(plan.launch.args.contains("--toolkit-l1-rpc=wss://ethereum-sepolia-rpc.publicnode.com"))
        assertTrue(plan.launch.args.contains("--toolkit-state=/data/starknet/sepolia"), "no disk layout: the node dir holds the DB")
    }

    @Test
    fun full_snapshot_keeps_all_history_unless_the_operator_sets_a_window()
    {
        val full = StarknetNodeStart().plan(ctx("mainnet", """{"snapshot":"full","l1_rpc":"wss://eth.example/ws/KEY"}"""))
        assertTrue(full.launch.args.contains("--toolkit-snapshot=full"))
        assertFalse(full.launch.args.any { it.startsWith("--toolkit-prune") })
        assertTrue(full.launch.args.contains("--toolkit-l1-rpc=wss://eth.example/ws/KEY"))
        val windowed = StarknetNodeStart().plan(ctx("mainnet", """{"snapshot":"full","prune_blocks":"250000"}"""))
        assertTrue(windowed.launch.args.contains("--toolkit-prune=250000"))
        val pruned = StarknetNodeStart().plan(ctx("mainnet", """{"prune_blocks":"500"}"""))
        assertTrue(pruned.launch.args.contains("--toolkit-snapshot=pruned"), "pruned is the default flavor")
        assertTrue(pruned.launch.args.contains("--toolkit-prune=500"))
        assertTrue(pruned.launch.args.contains("--toolkit-l1-rpc=wss://ethereum-rpc.publicnode.com"))
    }

    @Test
    fun state_role_of_the_disk_layout_is_the_db_path()
    {
        val plan = StarknetNodeStart().plan(ctx("mainnet", layout = """{"roles":[{"id":"state","dir":"/nvme/juno"}]}"""))
        assertTrue(plan.launch.args.contains("--toolkit-state=/nvme/juno"))
    }

    @Test
    fun options_only_take_plain_numbers_for_the_prune_window()
    {
        assertEquals("", StarknetOptions.parse("""{"prune_blocks":"128; rm -rf /"}""").pruneBlocks)
        assertEquals("", StarknetOptions.parse("""{"prune_blocks":"-1"}""").pruneBlocks)
        assertEquals("0", StarknetOptions.parse("""{"prune_blocks":"0"}""").pruneBlocks)
        assertEquals(StarknetChain.SNAPSHOT_PRUNED, StarknetOptions.parse("""{"snapshot":"archive"}""").snapshot)
        assertEquals(StarknetChain.SNAPSHOT_PRUNED, StarknetOptions.parse(null).snapshot)
        assertEquals("100000", StarknetOptions.parse("").pruneWindow())
        assertEquals(null, StarknetOptions.parse("""{"snapshot":"full"}""").pruneWindow())
    }
}
