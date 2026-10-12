package rpcnode.toolkit.chains.solana.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rpcnode.toolkit.chains.solana.infrastructure.SolanaClusters
import rpcnode.toolkit.chains.solana.infrastructure.SolanaRpcTuning
import rpcnode.toolkit.chains.solana.infrastructure.SolanaUnitBodies

class SolanaRpcTest
{
    @Test
    fun parse_slot_number()
    {
        assertEquals(440_750_412L, SolanaRpc.parseSlot("""{"jsonrpc":"2.0","id":1,"result":440750412}"""))
    }

    @Test
    fun parse_slot_rejects_error()
    {
        assertNull(
            SolanaRpc.parseSlot(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32005,"message":"Node is unhealthy"}}""",
            ),
        )
    }
}

class SolanaUnitBodiesTest
{
    @Test
    fun run_script_includes_limit_ledger_for_full()
    {
        val script = SolanaUnitBodies.runValidatorScript(
            bin = "/opt/solana/mainnet/bin/agave-validator",
            identity = "/etc/solana/mainnet/validator-keypair.json",
            ledger = "/data/solana/mainnet/ledger",
            accounts = "/data/solana/mainnet/accounts",
            snapshots = "/data/solana/mainnet/snapshots",
            logPath = "/data/solana/mainnet/solana-mainnet.log",
            rpcPort = 8899,
            p2pRange = "8000-8026",
            cluster = SolanaClusters.lookup("mainnet"),
            archive = false,
            egressReachable = false,
        )
        assertTrue(script.contains("--limit-ledger-size"))
        assertTrue(script.contains("--no-port-check"))
        assertTrue(script.contains("--expected-genesis-hash"))
        assertTrue(script.contains("--rpc-port 8899"))
        assertTrue(script.contains("--rpc-threads ${SolanaUnitBodies.RPC_THREADS}"))
        assertTrue(script.contains("--rpc-pubsub-worker-threads ${SolanaUnitBodies.RPC_PUBSUB_WORKER_THREADS}"))
        assertTrue(
            script.contains(
                "--rpc-pubsub-max-active-subscriptions ${SolanaUnitBodies.RPC_PUBSUB_MAX_ACTIVE_SUBSCRIPTIONS}",
            ),
        )
        assertTrue(script.contains("--rpc-max-request-body-size ${SolanaUnitBodies.RPC_MAX_REQUEST_BODY_SIZE}"))
        assertTrue(script.contains("--full-rpc-api"))
        // without these getTransaction / getSignaturesForAddress answer «-32011 Transaction history is not available»
        assertTrue(script.contains("--enable-rpc-transaction-history"))
        assertTrue(script.contains("--enable-extended-tx-metadata-storage"))
    }

    @Test
    fun tx_history_can_be_switched_off_and_defaults_on_for_old_panels()
    {
        fun script(tuning: SolanaRpcTuning) = SolanaUnitBodies.runValidatorScript(
            bin = "/b/agave-validator", identity = "/i.json", ledger = "/l", accounts = "/a", snapshots = "/s",
            logPath = "/l.log", rpcPort = 8899, p2pRange = "8000-8026", cluster = SolanaClusters.lookup("mainnet"),
            archive = false, egressReachable = false, tuning = tuning,
        )
        assertTrue(!script(SolanaRpcTuning(txHistory = false)).contains("--enable-rpc-transaction-history"))
        assertTrue(script(SolanaRpcTuning()).contains("--enable-rpc-transaction-history"))
        assertTrue(rpcnode.toolkit.chains.solana.infrastructure.solanaRpcTuning("""{"rpc_tx_history":"false"}""").txHistory.not())
        assertTrue(rpcnode.toolkit.chains.solana.infrastructure.solanaRpcTuning("""{"rpc_threads":"64"}""").txHistory)
        assertTrue(rpcnode.toolkit.chains.solana.infrastructure.solanaRpcTuning(null).txHistory)
    }

    @Test
    fun run_script_uses_custom_rpc_tuning()
    {
        val script = SolanaUnitBodies.runValidatorScript(
            bin = "/data/solana/bin/agave-validator",
            identity = "/data/solana/.toolkit/validator-keypair.json",
            ledger = "/data/solana/ledger",
            accounts = "/data/solana/accounts",
            snapshots = "/data/solana/snapshots",
            logPath = "/data/solana/logs/solana.log",
            rpcPort = 8899,
            p2pRange = "8000-8026",
            cluster = SolanaClusters.lookup("mainnet"),
            archive = false,
            egressReachable = false,
            tuning = SolanaRpcTuning(rpcThreads = 256, rpcPubsubWorkerThreads = 64),
        )
        assertTrue(script.contains("--rpc-threads 256"))
        assertTrue(script.contains("--rpc-pubsub-worker-threads 64"))
    }

    @Test
    fun unit_sets_nofile_headroom()
    {
        val body = SolanaUnitBodies.unit("mainnet", "/opt/solana/mainnet/run-validator.sh")
        assertTrue(body.contains("LimitNOFILE=${SolanaUnitBodies.NODE_NOFILE}"))
        assertTrue(body.contains("LimitMEMLOCK=infinity"))
        assertTrue(body.contains("CAP_NET_RAW"))
    }

    @Test
    fun run_script_omits_limit_ledger_for_archive()
    {
        val script = SolanaUnitBodies.runValidatorScript(
            bin = "/opt/solana/mainnet/bin/agave-validator",
            identity = "/etc/solana/mainnet/validator-keypair.json",
            ledger = "/data/solana/mainnet/ledger",
            accounts = "/data/solana/mainnet/accounts",
            snapshots = "/data/solana/mainnet/snapshots",
            logPath = "/data/solana/mainnet/solana-mainnet.log",
            rpcPort = 8899,
            p2pRange = "8000-8026",
            cluster = SolanaClusters.lookup("mainnet"),
            archive = true,
            egressReachable = true,
        )
        assertFalse(script.contains("--limit-ledger-size"))
        assertFalse(script.contains("--no-port-check"))
    }
}
