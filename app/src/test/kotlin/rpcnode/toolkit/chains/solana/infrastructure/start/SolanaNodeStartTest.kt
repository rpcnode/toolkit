package rpcnode.toolkit.chains.solana.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class SolanaNodeStartTest
{
    @Test
    fun plan_full_mode_default()
    {
        val plan = SolanaNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.SOLANA,
                env = "mainnet",
                program = "agave-validator",
                configFile = null,
                nodeDir = "/data/solana/mainnet/ledger",
                diskLayoutJson = """
                    {"ledger_dir":"/mnt/nvme0/solana/mainnet/ledger",
                     "accounts_dir":"/mnt/nvme1/solana/mainnet/accounts",
                     "snapshots_dir":"/mnt/ssd0/solana/mainnet/snapshots"}
                """.trimIndent(),
            ),
        )
        assertEquals("binary", plan.launch.kind)
        assertEquals("agave-validator", plan.launch.entry)
        assertEquals("logs/validator.log", plan.launch.logFile)
        assertEquals("solana_rpc", plan.height.kind)
        assertEquals("http", plan.height.portRole)
        assertTrue(plan.launch.args.contains("--toolkit-archive=0"))
        assertTrue(plan.launch.args.contains("--toolkit-env=mainnet"))
        assertTrue(plan.launch.args.contains("--toolkit-rpc-threads=128"))
        assertTrue(plan.launch.args.any { it.startsWith("--toolkit-ledger=") })
        assertTrue(plan.launch.args.any { it.startsWith("--toolkit-accounts=") })
        assertTrue(plan.launch.args.any { it.startsWith("--toolkit-snapshots=") })
    }

    @Test
    fun plan_rpc_tuning_from_install_options()
    {
        val plan = SolanaNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.SOLANA,
                env = "testnet",
                program = "agave-validator",
                configFile = null,
                installOptionsJson = """{"rpc_threads":"256","LimitNOFILE":"1048576"}""",
            ),
        )
        assertTrue(plan.launch.args.contains("--toolkit-rpc-threads=256"))
        assertTrue(plan.launch.args.contains("--toolkit-limit-nofile=1048576"))
    }

    @Test
    fun plan_archive_from_install_options()
    {
        val plan = SolanaNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.SOLANA,
                env = "devnet",
                program = "agave-validator",
                configFile = null,
                installOptionsJson = """{"node":"archive"}""",
            ),
        )
        assertTrue(plan.launch.args.contains("--toolkit-archive=1"))
        assertTrue(plan.launch.args.contains("--toolkit-env=devnet"))
    }
}
