package rpcnode.toolkit.chains.xai.infrastructure

/**
 * Static Xai facts: an Arbitrum Orbit AnyTrust chain whose PARENT is Arbitrum One (an L3), run by stock Nitro.
 * Mainnet only — no Xai Sepolia is documented.
 */
object XaiChain
{
    const val ENV = "mainnet"

    /** `chain-name` of the chain-info entry; with `--chain.info-files` it selects the chain (Nitro `ProcessChainInfo`). */
    const val CHAIN_NAME = "Xai"
    const val CHAIN_ID = 660279L
    const val PARENT_CHAIN_ID = 42161L

    /** Classpath copy of the chain-info JSON (no public CDN copy exists); written to [CHAIN_INFO_FILE] on Start. */
    const val CHAIN_INFO_RESOURCE = "chains/xai/xai-chain-info.json"
    const val CHAIN_INFO_FILE = ".toolkit/xai-chain-info.json"

    /** 0600 file with the parent-chain RPC (`NITRO_PARENT__CHAIN_CONNECTION_URL`), read through `--conf.env-prefix=NITRO`. */
    const val PARENT_ENV_FILE = ".toolkit/xai-parent.env"
    const val ENV_PREFIX = "NITRO"
    const val PARENT_ENV_KEY = "NITRO_PARENT__CHAIN_CONNECTION_URL"

    /** Sequencer feed (`--node.feed.input.url`). */
    const val FEED_URL = "wss://xai-chain.net/feed"

    /**
     * `--execution.forwarding-target`: Nitro refuses to start without it ("ForwardingTarget not set and not sequencer");
     * the chain-info file does not carry it. The chain's public RPC forwards.
     */
    const val FORWARDING_TARGET = "https://xai-chain.net/rpc"

    /** AnyTrust REST aggregator: this URL answers with the list of the committee's REST servers. */
    const val DAS_URL_LIST = "https://xai-chain.net/das-servers"

    /** Public Arbitrum One RPC: rate limited, only a stop-gap default (see `l1Parent` in network.yml). */
    const val DEFAULT_PARENT_RPC = "https://arb1.arbitrum.io/rpc"

    const val HTTP_API = "net,web3,eth"
    const val WS_API = "net,web3,eth"
    const val RPC_MAX_BATCH_RESPONSE_SIZE = "25000000"
    const val FILTER_LOG_CACHE_SIZE = "256"
    const val PERSISTENT_HANDLES = "16384"
    const val LIMIT_NOFILE = "1048576"

    @Suppress("UNUSED_PARAMETER")
    fun normalizeEnv(raw: String): String = ENV
}

/** Catalog-fixed ports — must match `chains/xai/clients.yml`. */
data class XaiPorts(val http: Int, val ws: Int, val metrics: Int)

object XaiPortTable
{
    val MAINNET = XaiPorts(http = 42710, ws = 42711, metrics = 42714)

    @Suppress("UNUSED_PARAMETER")
    fun forEnv(env: String): XaiPorts = MAINNET
}
