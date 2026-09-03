package rpcnode.toolkit.chains.solana.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Renders Agave systemd unit + run-validator.sh from classpath templates under
 * `resources/chains/solana/` (`node.service.tmpl`, `scripts/run-validator.sh.tmpl`).
 * Primary unit name stays `rpcnode-solana-<env>` (HostNodeLaunchSupport).
 */
object SolanaUnitBodies
{
    const val RPC_THREADS = 128
    const val RPC_PUBSUB_WORKER_THREADS = 32
    const val RPC_PUBSUB_MAX_ACTIVE_SUBSCRIPTIONS = 5_000_000
    const val RPC_MAX_REQUEST_BODY_SIZE = 104_857_600
    const val NODE_NOFILE = 4_194_304

    /** Classpath / clients-dir companion template (shipped on Solana client download). */
    const val RUN_VALIDATOR_TMPL = "scripts/run-validator.sh.tmpl"
    const val RUN_VALIDATOR_NAME = "run-validator.sh"
    const val RUN_VALIDATOR_TMPL_NAME = "run-validator.sh.tmpl"

    fun unit(env: String, scriptPath: String, nofile: Int = NODE_NOFILE): String
    {
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("solana", "node.service.tmpl"),
            mapOf(
                "description" to "Solana Agave RPC ($env, non-voting) — RpcNode",
                "script_path" to scriptPath,
                "nofile" to nofile.coerceAtLeast(1024).toString(),
            ),
        )
    }

    /**
     * Prefer a template already synced into [nodeDir] (from panel clients dir);
     * fall back to classpath `chains/solana/scripts/`.
     */
    fun loadRunValidatorTemplate(nodeDir: java.nio.file.Path? = null): String
    {
        if (nodeDir != null)
        {
            val shipped = nodeDir.resolve(RUN_VALIDATOR_TMPL_NAME)
            if (java.nio.file.Files.isRegularFile(shipped))
            {
                return java.nio.file.Files.readString(shipped).trimEnd() + "\n"
            }
        }
        return HostSystemdUnitTemplate.load("solana", RUN_VALIDATOR_TMPL)
    }

    fun runValidatorScript(
        bin: String,
        identity: String,
        ledger: String,
        accounts: String,
        snapshots: String,
        logPath: String,
        rpcPort: Int,
        p2pRange: String,
        cluster: SolanaCluster,
        archive: Boolean,
        egressReachable: Boolean,
        tuning: SolanaRpcTuning = SolanaRpcTuning(),
        template: String = loadRunValidatorTemplate(null),
    ): String
    {
        val binDir = bin.substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { "." }
        val flags = mutableListOf<String>()
        flags += "--identity ${shellQuote(identity)}"
        for (v in cluster.knownValidators)
        {
            flags += "--known-validator $v"
        }
        if (cluster.onlyKnownRpc && cluster.knownValidators.isNotEmpty())
        {
            flags += "--only-known-rpc"
        }
        flags += "--no-voting"
        flags += "--no-poh-speed-test"
        flags += "--private-rpc"
        if (!egressReachable)
        {
            flags += "--no-port-check"
            flags += "--no-xdp"
        }
        flags += "--rpc-port $rpcPort"
        flags += "--rpc-bind-address 127.0.0.1"
        if (p2pRange.isNotBlank())
        {
            flags += "--dynamic-port-range $p2pRange"
        }
        for (ep in cluster.entrypoints)
        {
            flags += "--entrypoint $ep"
        }
        if (cluster.genesis.isNotBlank())
        {
            flags += "--expected-genesis-hash ${cluster.genesis}"
        }
        flags += "--full-rpc-api"
        flags += "--rpc-threads ${tuning.rpcThreads.coerceAtLeast(1)}"
        flags += "--rpc-pubsub-worker-threads ${tuning.rpcPubsubWorkerThreads.coerceAtLeast(1)}"
        flags += "--rpc-pubsub-max-active-subscriptions ${tuning.rpcPubsubMaxActiveSubscriptions.coerceAtLeast(1)}"
        flags += "--rpc-max-request-body-size ${tuning.rpcMaxRequestBodySize.coerceAtLeast(1024)}"
        flags += "--ledger ${shellQuote(ledger)}"
        flags += "--accounts ${shellQuote(accounts)}"
        if (snapshots.isNotBlank())
        {
            flags += "--snapshots ${shellQuote(snapshots)}"
        }
        if (!archive)
        {
            flags += "--limit-ledger-size"
        }
        flags += "--wal-recovery-mode skip_any_corrupted_record"
        flags += "--log ${shellQuote(logPath)}"

        val execFlags = flags.mapIndexed { i, flag ->
            if (i == flags.lastIndex) "  $flag" else "  $flag \\"
        }.joinToString("\n")

        return HostSystemdUnitTemplate.render(
            template,
            mapOf(
                "bin_dir" to shellQuote(binDir),
                "bin" to shellQuote(bin),
                "exec_flags" to execFlags,
            ),
        )
    }

    fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"
}
