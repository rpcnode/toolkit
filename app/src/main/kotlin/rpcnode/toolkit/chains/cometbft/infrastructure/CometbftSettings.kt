package rpcnode.toolkit.chains.cometbft.infrastructure

/** One `config.toml` / `app.toml` value. [value] is the unquoted value as it appears in `network.yml` bindings. */
data class TomlSetting(
    val file: String,
    val section: String,
    val key: String,
    val value: String,
    val kind: Kind = Kind.STRING,
)
{
    enum class Kind { STRING, RAW, LIST }

    /** Binding key in `network.yml`: `section.key`, or just `key` at the top level. */
    val bindingKey: String get() = if (section.isEmpty()) key else "$section.$key"

    fun tomlValue(): String = when (kind)
    {
        Kind.STRING -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        Kind.RAW -> value
        Kind.LIST -> "[\"" + value + "\"]"
    }
}

/**
 * The capacity / exposure values applied to `config.toml` and `app.toml` on every Start. The literals equal the
 * `clientConfig.bindings` of each network.yml (a test compares them), so what the panel shows is what runs.
 */
object CometbftSettings
{
    const val CONFIG = "config.toml"
    const val APP = "app.toml"

    const val RPC_MAX_OPEN_CONNECTIONS = 2000
    const val RPC_MAX_SUBSCRIPTION_CLIENTS = 100
    const val RPC_MAX_SUBSCRIPTIONS_PER_CLIENT = 5
    const val RPC_TIMEOUT_BROADCAST = "10s"
    const val RPC_MAX_BODY_BYTES = 1_000_000
    const val P2P_INBOUND = 40
    const val P2P_OUTBOUND = 10
    const val SEI_P2P_MAX_CONNECTIONS = 200
    const val MEMPOOL_SIZE = 5000
    const val MEMPOOL_MAX_TXS_BYTES = 1_073_741_824L
    const val API_MAX_OPEN_CONNECTIONS = 1000
    const val API_MAX_BODY_BYTES = 1_000_000
    const val GRPC_MAX_RECV = 10_485_760
    const val EVM_API = "eth,net,web3"
    const val NOFILE = 1_048_576

    // cosmos/evm `[json-rpc]` (XRPL EVM, Injective): the module's own defaults, written down so the panel shows them
    const val EVM_GAS_CAP = 25_000_000
    const val EVM_TIMEOUT = "5s"
    const val EVM_LOGS_CAP = 10_000
    const val EVM_BLOCK_RANGE_CAP = 10_000
    const val EVM_FILTER_CAP = 200
    const val EVM_BATCH_LIMIT = 1000

    // Sei `[evm]` / `[state-store]` (docs.sei.io node operations guide)
    const val SEI_MAX_LOG_NO_BLOCK = 10_000
    const val SEI_MAX_BLOCKS_FOR_LOG = 2000
    const val SEI_MAX_TRACE_CALLS = 10
    const val SEI_MAX_SUBS_NEW_HEAD = 10_000
    const val SEI_MAX_SUBS_LOGS = 1000
    const val SEI_READ_TIMEOUT = "30s"
    const val SEI_SS_KEEP_RECENT = 100_000

    fun forEnv(profile: CometbftProfile, env: CometbftEnv): List<TomlSetting>
    {
        val p = profile.ports(env)
        val snake = profile.style == KeyStyle.SNAKE
        fun k(name: String) = if (snake) name else name.replace('_', '-')
        val out = mutableListOf<TomlSetting>()
        fun cfg(section: String, key: String, value: String, kind: TomlSetting.Kind = TomlSetting.Kind.STRING) {
            out += TomlSetting(CONFIG, if (snake) section else section.replace('_', '-'), k(key), value, kind)
        }
        fun app(section: String, key: String, value: String, kind: TomlSetting.Kind = TomlSetting.Kind.STRING) {
            out += TomlSetting(APP, section, key, value, kind)
        }
        val raw = TomlSetting.Kind.RAW
        cfg("rpc", "laddr", "tcp://0.0.0.0:${p.rpc}")
        cfg("rpc", "unsafe", "false", raw)
        cfg("rpc", "pprof_laddr", "")
        cfg("rpc", "max_open_connections", "$RPC_MAX_OPEN_CONNECTIONS", raw)
        cfg("rpc", "max_subscription_clients", "$RPC_MAX_SUBSCRIPTION_CLIENTS", raw)
        cfg("rpc", "max_subscriptions_per_client", "$RPC_MAX_SUBSCRIPTIONS_PER_CLIENT", raw)
        cfg("rpc", "timeout_broadcast_tx_commit", RPC_TIMEOUT_BROADCAST)
        cfg("rpc", "max_body_bytes", "$RPC_MAX_BODY_BYTES", raw)
        cfg("p2p", "laddr", "tcp://0.0.0.0:${p.p2p}")
        if (env.seeds.isNotBlank())
        {
            cfg("p2p", "seeds", env.seeds)
        }
        if (profile.evm == EvmKind.SEI)
        {
            // The Sei fork has one connection cap instead of inbound / outbound slots.
            cfg("p2p", "max_connections", "$SEI_P2P_MAX_CONNECTIONS", raw)
        }
        else
        {
            cfg("p2p", "max_num_inbound_peers", "$P2P_INBOUND", raw)
            cfg("p2p", "max_num_outbound_peers", "$P2P_OUTBOUND", raw)
        }
        cfg("mempool", "size", "$MEMPOOL_SIZE", raw)
        cfg("mempool", "max_txs_bytes", "$MEMPOOL_MAX_TXS_BYTES", raw)
        if (profile.evm == EvmKind.SEI)
        {
            cfg("tx_index", "indexer", "kv", TomlSetting.Kind.LIST)
        }
        else
        {
            cfg("tx_index", "indexer", "kv")
        }
        cfg("instrumentation", "prometheus", "true", raw)
        cfg("instrumentation", "prometheus_listen_addr", "127.0.0.1:${p.metrics}")

        app("", "minimum-gas-prices", env.minGasPrice)
        app("", "pruning", "default")
        app("", "min-retain-blocks", "${profile.minRetainBlocks}", raw)
        app("api", "enable", "true", raw)
        app("api", "swagger", "false", raw)
        app("api", "address", "tcp://0.0.0.0:${p.api}")
        app("api", "max-open-connections", "$API_MAX_OPEN_CONNECTIONS", raw)
        app("api", "rpc-max-body-bytes", "$API_MAX_BODY_BYTES", raw)
        app("api", "enabled-unsafe-cors", "false", raw)
        app("grpc", "enable", "true", raw)
        app("grpc", "address", "0.0.0.0:${p.grpc}")
        app("grpc", "max-recv-msg-size", "$GRPC_MAX_RECV", raw)
        app("grpc-web", "enable", "false", raw)
        app("state-sync", "snapshot-interval", "0", raw)
        env.timeoutCommit?.let { cfg("consensus", "timeout_commit", it) }
        when (profile.evm)
        {
            EvmKind.COSMOS_EVM ->
            {
                app("json-rpc", "enable", "true", raw)
                app("json-rpc", "address", "0.0.0.0:${p.evmHttp}")
                app("json-rpc", "ws-address", "0.0.0.0:${p.evmWs}")
                app("json-rpc", "api", EVM_API)
                app("json-rpc", "allow-unprotected-txs", "false", raw)
                app("json-rpc", "enable-profiling", "false", raw)
                app("json-rpc", "gas-cap", "$EVM_GAS_CAP", raw)
                app("json-rpc", "evm-timeout", EVM_TIMEOUT)
                app("json-rpc", "logs-cap", "$EVM_LOGS_CAP", raw)
                app("json-rpc", "block-range-cap", "$EVM_BLOCK_RANGE_CAP", raw)
                app("json-rpc", "filter-cap", "$EVM_FILTER_CAP", raw)
                app("json-rpc", "batch-request-limit", "$EVM_BATCH_LIMIT", raw)
                env.evmChainId?.let { app("evm", "evm-chain-id", it, raw) }
            }
            EvmKind.SEI ->
            {
                app("evm", "http_port", "${p.evmHttp}", raw)
                app("evm", "ws_port", "${p.evmWs}", raw)
                app("admin_server", "admin_enabled", "false", raw)
                app("evm", "max_log_no_block", "$SEI_MAX_LOG_NO_BLOCK", raw)
                app("evm", "max_blocks_for_log", "$SEI_MAX_BLOCKS_FOR_LOG", raw)
                app("evm", "max_concurrent_trace_calls", "$SEI_MAX_TRACE_CALLS", raw)
                app("evm", "max_subscriptions_new_head", "$SEI_MAX_SUBS_NEW_HEAD", raw)
                app("evm", "max_subscriptions_logs", "$SEI_MAX_SUBS_LOGS", raw)
                app("evm", "read_timeout", SEI_READ_TIMEOUT)
                app("evm", "cors_origins", "*")
                app("state-store", "ss-backend", "pebbledb")
                app("state-store", "ss-keep-recent", "$SEI_SS_KEEP_RECENT", raw)
                app("receipt-store", "rs-backend", "pebbledb")
            }
            EvmKind.NONE -> Unit
        }
        return out
    }
}
