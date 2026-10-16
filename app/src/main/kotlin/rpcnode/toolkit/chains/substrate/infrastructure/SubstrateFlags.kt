package rpcnode.toolkit.chains.substrate.infrastructure

/**
 * Capacity literals of a Substrate node. Each one is also a `literal` binding of `chains/<id>/network.yml`
 * (`SubstrateCatalogTest` keeps them equal), so Start's Config preview shows what the unit applies.
 * Flag names and defaults: `sc-cli` at polkadot-stable2609 (`rpc_params.rs`, `network_params.rs`, `import_params.rs`, `pruning_params.rs`).
 */
object SubstrateSettings
{
    /** Vendor default 100; docs.polkadot.com's own RPC-node recipe runs 1000. Also bounds the RPC blocking-thread pool. */
    const val RPC_MAX_CONNECTIONS = 1000
    const val RPC_MAX_REQUEST_MB = 15
    const val RPC_MAX_RESPONSE_MB = 15
    const val RPC_MAX_SUBSCRIPTIONS = 1024
    const val RPC_MESSAGE_BUFFER = 64
    const val TRIE_CACHE_BYTES = 1_073_741_824L
    const val IN_PEERS = 32
    const val OUT_PEERS = 8
    const val PRUNING_BLOCKS = 256

    const val RPC_METHODS = "safe"
    const val RPC_CORS = "all"
}

/**
 * The command line of a Substrate relay-chain node (`polkadot`). Built by one function so the unit, the catalog test and
 * the Start preview cannot drift; flags are `--key=value` (or bare), one per entry.
 */
object SubstrateFlags
{
    /** Database / pruning / sync flags of a node type: the wizard's `snapshot` type decides, a snapshot must match it. */
    fun modeFlags(env: SubstrateEnv, mode: SubstrateMode): List<String> = when (mode)
    {
        SubstrateMode.PRUNED -> listOf(
            "--database=${SubstrateDb.PARITYDB.flag}",
            "--sync=warp",
            "--state-pruning=${SubstrateSettings.PRUNING_BLOCKS}",
            "--blocks-pruning=${SubstrateSettings.PRUNING_BLOCKS}",
        )
        SubstrateMode.ARCHIVE -> listOf(
            "--database=${env.archiveDb.flag}",
            "--sync=full",
            "--state-pruning=archive",
            "--blocks-pruning=archive",
        )
    }

    /** RPC server flags shared by every Substrate unit: public port with the safe method set, Prometheus stays on loopback. */
    fun rpcFlags(rpcPort: Int, metricsPort: Int): List<String> = listOf(
        "--rpc-port=$rpcPort",
        "--rpc-external",
        "--rpc-methods=${SubstrateSettings.RPC_METHODS}",
        "--rpc-cors=${SubstrateSettings.RPC_CORS}",
        "--rpc-max-connections=${SubstrateSettings.RPC_MAX_CONNECTIONS}",
        "--rpc-max-request-size=${SubstrateSettings.RPC_MAX_REQUEST_MB}",
        "--rpc-max-response-size=${SubstrateSettings.RPC_MAX_RESPONSE_MB}",
        "--rpc-max-subscriptions-per-connection=${SubstrateSettings.RPC_MAX_SUBSCRIPTIONS}",
        "--rpc-message-buffer-capacity-per-connection=${SubstrateSettings.RPC_MESSAGE_BUFFER}",
        // 127.0.0.1 only: `--prometheus-external` is never passed
        "--prometheus-port=$metricsPort",
    )

    fun relayChain(spec: SubstrateSpec, env: SubstrateEnv, mode: SubstrateMode, basePath: String): List<String>
    {
        val ports = spec.ports(env)
        return listOf(
            "--chain=${env.chain}",
            "--base-path=$basePath",
            "--name=rpcnode-${env.id}",
            "--no-telemetry",
            "--port=${ports.p2p}",
            "--in-peers=${SubstrateSettings.IN_PEERS}",
            "--out-peers=${SubstrateSettings.OUT_PEERS}",
            "--trie-cache-size=${SubstrateSettings.TRIE_CACHE_BYTES}",
        ) + modeFlags(env, mode) + rpcFlags(ports.rpc, ports.metrics)
    }
}
