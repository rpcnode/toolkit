package rpcnode.toolkit.chains.cardano.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class CardanoNodeStartTest
{
    private fun ctx(env: String, layout: String = "") = ChainNodeStartContext(
        network = NetworkId.CARDANO,
        env = env,
        program = "cardano-node",
        configFile = null,
        nodeDir = "/data/rpcnode/cardano/$env",
        installOptionsJson = "",
        diskLayoutJson = layout,
    )

    @Test
    fun plan_encodes_env_blockchain_role_and_metrics_height()
    {
        val plan = CardanoNodeStart().plan(ctx("preprod", """{"roles":[{"id":"blockchain","dir":"/mnt/nvme/cardano"}]}"""))
        assertEquals("binary", plan.launch.kind)
        assertEquals("bin/cardano-node", plan.launch.entry)
        assertTrue(plan.launch.args.contains("--toolkit-env=preprod"))
        assertTrue(plan.launch.args.contains("--toolkit-blockchain=/mnt/nvme/cardano"))
        assertEquals("cardano_metrics", plan.height.kind)
        assertEquals("metrics", plan.height.portRole)
    }

    @Test
    fun blockchain_falls_back_to_node_dir()
    {
        val plan = CardanoNodeStart().plan(ctx("mainnet"))
        assertTrue(plan.launch.args.contains("--toolkit-blockchain=/data/rpcnode/cardano/mainnet"))
    }

    @Test
    fun indexer_option_and_disk_role_travel_in_the_launch_args()
    {
        val layout = """{"roles":[{"id":"blockchain","dir":"/mnt/a/cardano"},{"id":"indexer","dir":"/mnt/b/cardano-idx"}]}"""
        val plan = CardanoNodeStart().plan(ctx("mainnet", layout).copy(installOptionsJson = """{"snapshot":"mithril","indexer":"both"}"""))
        assertTrue(plan.launch.args.contains("--toolkit-indexer=both"))
        assertTrue(plan.launch.args.contains("--toolkit-indexer-dir=/mnt/b/cardano-idx"))
        // the node height is still the node's own Prometheus
        assertEquals("cardano_metrics", plan.height.kind)
        assertEquals("metrics", plan.height.portRole)
    }

    @Test
    fun no_indexer_option_means_ogmios_and_no_indexer_dir()
    {
        val plan = CardanoNodeStart().plan(ctx("preview"))
        assertTrue(plan.launch.args.contains("--toolkit-indexer=ogmios"))
        assertTrue(plan.launch.args.none { it.startsWith("--toolkit-indexer-dir=") })
    }
}
