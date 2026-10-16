/**
 * Production RPC recommendations per network, checked against each client's own flag reference.
 * `flags` go to install_options.extra_flags (replace an existing unit flag or append a new one);
 * `conf` are config-file keys (ini / yaml / cfg) offered in the Node config document editor.
 * Values are a starting point for "thousands of requests per second" on ONE node — measure, then adjust.
 */
export type ProdPreset = {
  /** Flag overrides, key without the leading `--`. */
  flags?: Record<string, string>
  /** How flag keys are shown: `--` for command-line flags, `` for config-section keys (xrpld.cfg). */
  prefix?: string
  /** Config file keys to set in the Node config document editor (Bitcoin-family conf, java-tron config). */
  conf?: Record<string, string>
  /** Plain-language notes shown next to the button (RAM needs, what is NOT changed). */
  notes?: string[]
}

const NITRO: ProdPreset = {
  // Verified against Nitro v3.12.1 (flag registrations + docs.arbitrum.io CLI reference). Memory caches are not
  // changed here: size them with the GOMEMLIMIT formula from docs.arbitrum.io/run-a-node/nitro/node-tuning-and-monitoring.
  flags: {
    // debug_* is the main memory/CPU hazard on a public port, and the toolkit unit currently exposes it.
    'http.api': 'net,web3,eth',
    'ws.api': 'net,web3,eth',
    // batches of eth_getLogs / receipts exceed the 10 MB default (geth uses 25 MB)
    'rpc.max-batch-response-size': '25000000',
    'execution.rpc.filter-log-cache-size': '256',
    // Pebble table cache: the default 512 files evicts constantly on a TB-scale database (LimitNOFILE is 1048576)
    'persistent.handles': '16384',
    // smaller L1 read window → the delayed-message eth_getLogs stays under the geth 1000-topic limit
    'node.inbox-reader.default-blocks-to-read': '25',
    'node.inbox-reader.target-messages-read': '200',
  },
  notes: [
    'Drops the debug namespace from the public ports (keep it on a private port).',
    'Window flags for the L1 reader reduce "exceed max topics"; they add some L1 RPC calls.',
    'Cache sizes are not changed — set them from the RAM formula in the Arbitrum tuning guide.',
  ],
}

// reth family (base-reth-node, op-reth, arc-node-execution). Verified in the reth trees each client is built from
// (rpc_server.rs / rpc_state_cache.rs / network.rs). clap rejects a repeated flag, which is why the host REPLACES the
// unit's flag instead of appending. Cache sizes are by item count: budget the RAM before raising them further.
const RETH_RPC: Record<string, string> = {
  // 500 (Arc: 250) connections is a hard cap on HTTP+WS: thousands of clients are rejected above it
  'rpc.max-connections': '3000',
  // 160 MB responses from eth_getLogs / big blocks exhaust memory under concurrency
  'rpc.max-response-size': '64',
  // the wide-range getLogs scan is the costliest read path (default 100000 blocks)
  'rpc.max-blocks-per-filter': '50000',
  'rpc.evm-memory-limit': '134217728',
  'rpc-cache.max-blocks': '10000',
  'rpc-cache.max-receipts': '20000',
  'rpc-cache.max-headers': '5000',
  'rpc-cache.max-cached-tx-hashes': '100000',
}
const RETH_NOTES = [
  'Public namespaces reduced to eth,net,web3,rpc: debug / trace / txpool no longer on the public port.',
  'Caches are item counts — plan ~64 GB RAM or more before raising them further.',
  'Flags are checked against the pinned (or current latest) client; an unknown flag makes the node exit.',
]

// OP Stack family (chains/opstack: unichain, ink, soneium, worldchain, lisk, mode, zora). The run script already serves only
// eth,net,web3 publicly and passes --rollup.disable-tx-pool-gossip, so unlike `optimism` the preset only raises the RPC limits.
// Flags verified in the reth trees of op-reth v2.6.0 (ethereum-optimism/reth 0fbe428) and world-chain v2.4.5 (op-rs/reth aef8d3e).
const OPSTACK: ProdPreset = {
  flags: {
    ...RETH_RPC,
    // blocks come from op-node; EL p2p is not needed for serving RPC
    'max-outbound-peers': '40',
    'max-inbound-peers': '20',
  },
  notes: [
    'The unit already serves eth,net,web3 only (no debug / trace / txpool / admin) and does not gossip transactions; this preset raises the RPC limits.',
    'The Engine API, both metrics ports and the op-node RPC are bound to 127.0.0.1 by the run script.',
    RETH_NOTES[1],
    RETH_NOTES[2],
    'Applies to op-reth / world-chain only (op-node flags are not overridden).',
  ],
}

export const PROD_PRESETS: Record<string, ProdPreset> = {
  arb: NITRO,
  robinhood: NITRO,
  xai: {
    // The unit already ships these values (network.yml bindings); the preset restores them after manual edits.
    // Same Nitro v3.12.1 flags as NITRO, minus the two inbox-reader window keys: they work around geth's 1000-topic
    // eth_getLogs limit of an Ethereum parent, and Xai's parent is Arbitrum One (INFERRED not needed, not tested).
    flags: {
      'http.api': 'net,web3,eth',
      'ws.api': 'net,web3,eth',
      'rpc.max-batch-response-size': '25000000',
      'execution.rpc.filter-log-cache-size': '256',
      'persistent.handles': '16384',
    },
    notes: [
      'Public ports expose net,web3,eth only: debug_* is the main memory/CPU hazard of a public Nitro.',
      'Metrics stay on 127.0.0.1. The Arbitrum One RPC URL lives in a 0600 env file, not in the unit.',
      'Cache sizes are not changed — set them from the RAM formula in the Arbitrum tuning guide.',
      'No snapshot exists: the first sync replays the chain from genesis (days). Use a private Arbitrum One RPC.',
    ],
  },
  starknet: {
    // Juno v0.16.8 flag table (docs/_config-options.md). The unit already ships these values; defaults in brackets.
    flags: {
      'db-cache-size': '4096', // [1024] MB of Pebble block cache; the Juno docs suggest 2048-4096 on a RAM-rich host
      'rpc-max-block-scan': '10000', // [unlimited] bounds one starknet_getEvents scan
      'rpc-request-timeout': '30s', // [1m]
      'rpc-max-concurrent-requests': '4096', // [256000]
      'rpc-max-request-queue': '4096', // [256000]
    },
    notes: [
      'Juno has no debug namespace to hide; the unit keeps pprof, gRPC, p2p, the log-level port and metrics off the public interfaces.',
      'Pruning (--prune-mode) is set at Start (prune_blocks option); a pruned snapshot starts with a 128-block window.',
      'Plan about 16 GB RAM on mainnet with db-cache-size 4096.',
    ],
  },
  base: {
    flags: {
      ...RETH_RPC,
      'http.api': 'eth,net,web3,rpc',
      'ws.api': 'eth,net,web3,rpc',
      // blocks come from the consensus layer; EL p2p is not needed for serving RPC
      'max-outbound-peers': '40',
      'max-inbound-peers': '20',
    },
    notes: RETH_NOTES,
  },
  optimism: {
    flags: {
      ...RETH_RPC,
      'http.api': 'eth,net,web3,rpc',
      'ws.api': 'eth,net,web3,rpc',
      'disable-tx-gossip': '',
      'max-outbound-peers': '40',
      'max-inbound-peers': '20',
    },
    notes: [...RETH_NOTES, 'Applies to op-reth only (op-node flags are not overridden).'],
  },
  unichain: OPSTACK,
  ink: OPSTACK,
  soneium: OPSTACK,
  worldchain: OPSTACK,
  lisk: OPSTACK,
  mode: OPSTACK,
  zora: OPSTACK,
  arc: {
    flags: {
      ...RETH_RPC,
      'http.api': 'eth,net,web3,rpc',
      // Arc: hides pending-tx RPCs; its docs require this with --http.api eth,net,web3,rpc for a public node
      'public-api': '',
    },
    notes: [
      ...RETH_NOTES,
      'Arc docs forbid --rpc.forwarder on provider nodes — this preset cannot remove it; check the launch script.',
    ],
  },
  bsc: {
    flags: {
      // txpool is public and heavy; parlia is BSC-specific
      'http.api': 'eth,net,web3,parlia',
      // hard-caps eth_getLogs at 5000 blocks per query
      rangelimit: '',
      // the unit sets 2000: a larger batch cap only amplifies per-request work
      'rpc.batch-request-limit': '1000',
      'cache.blocklogs': '128',
      // official README command line keeps the full transaction index (no effect on a pruneancient snapshot)
      'history.transactions': '0',
      // RPC nodes gain nothing from 100 peers; fewer peers free CPU/bandwidth for serving (block propagation stays fine)
      maxpeers: '50',
      // eth_call / estimateGas ceiling per request (geth default 50M) — bounds the cost of one heavy call
      'rpc.gascap': '25000000',
      // shed runaway eth_call sooner than the 5s default so they do not hold a worker under load
      'rpc.evmtimeout': '3s',
      // Prometheus endpoint on 127.0.0.1:6060 (default address) — needed to see cache hit-rate / sync lag during load
      metrics: '',
    },
    notes: [
      'Checked against bnb-chain/bsc v1.7.8.',
      'Leave --cache alone unless you know the host RAM (geth caps it at RAM/3).',
      'rpc.gascap / rpc.evmtimeout / maxpeers are judgement values: a call needing more than 25M gas or 3s will now fail.',
      '--metrics listens on 127.0.0.1:6060 only; scrape it locally or via an exporter.',
      '--rpc.allow-unprotected-txs is no longer in the unit (pre-EIP-155 transactions are rejected). Put a rate-limiting proxy in front.',
    ],
  },
  etc: {
    flags: {
      // public namespaces: eth,net,web3 only (txpool_content dumps the whole pool)
      'http.api': 'eth,net,web3',
      // smaller batches limit per-request amplification (judgement)
      'rpc.batch-request-limit': '500',
      'rpc.batch-response-max-size': '10000000',
      // eth_call is the costliest public call (judgement)
      'rpc.evmtimeout': '3s',
      'rpc.gascap': '25000000',
    },
    notes: [
      'Checked against core-geth v1.13.0 (docs.coregeth.com Public RPC endpoint guide).',
      'The node has no TLS, auth, rate limit or eth_getLogs block-range limit: put a rate-limiting proxy in front.',
    ],
  },
  // Nethermind: PascalCase keys (case-sensitive, VERIFIED in IJsonRpcConfig / INetworkConfig 2.1.0). The unit already runs the public
  // modules and 1000 / 500 / 50; these are tighter starting points. EnabledModules without Subscribe removes eth_subscribe on WS.
  gnosis: {
    flags: {
      'JsonRpc.MaxBatchSize': '500',
      'JsonRpc.RequestQueueLimit': '1000',
      'Network.MaxActivePeers': '50',
      'JsonRpc.EnabledModules': 'Eth,Net,Web3',
    },
    notes: [
      'Nethermind flag names are case-sensitive; ports, paths, hosts and the JWT cannot be overridden.',
      'EnabledModules Eth,Net,Web3 drops Subscribe and Rpc (no eth_subscribe over WebSocket).',
      'Put a rate-limiting proxy in front: the RPC port has no auth or rate limit.',
    ],
  },
  joc: {
    flags: {
      'JsonRpc.MaxBatchSize': '500',
      'JsonRpc.RequestQueueLimit': '1000',
      'JsonRpc.EnabledModules': 'Eth,Net,Web3',
    },
    notes: ['Nethermind flag names are case-sensitive. Clique PoA chain: block time 5 s, small validator set.'],
  },
  kaia: {
    flags: {
      'rpc.concurrencylimit': '2000',
      rpcexecutiontimeout: '10',
      'rpc.gascap': '25000000',
      'rpc.evmtimeout': '3s',
    },
    notes: [
      'Flag names checked in kaiachain/kaia cmd/utils/flags.go v2.2.2 (rpc.gascap, rpc.evmtimeout exist); the values are judgement.',
      '--prometheusport listens on ALL interfaces: firewall it. debug / admin / personal / txpool are not exposed.',
    ],
  },
  sonic: {
    flags: {
      'rpc.gascap': '25000000',
      'rpc.evmtimeout': '3s',
      'rpc.batch-request-limit': '500',
      maxpeers: '30',
    },
    notes: [
      'Flag names checked in 0xsoniclabs/sonic v2.2.5 (config/flags). The unit already exposes eth,net,web3,sonic only.',
      '--cache must stay at or above 6144 (sonicd refuses less); the unit sets 12000.',
    ],
  },
  vana: {
    flags: {
      'rpc.batch-request-limit': '500',
      'rpc.evmtimeout': '3s',
      'rpc.gascap': '25000000',
    },
    notes: ['The unit already replaces the official compose values (5000 / 125 MB / unlimited EVM time) with 1000 / 25 MB / 5 s.'],
  },
  tempo: {
    flags: { ...RETH_RPC, 'http.api': 'eth,net,web3', 'ws.api': 'eth,net,web3' },
    notes: [
      ...RETH_NOTES,
      'Tempo pins its own reth revision: check the cache flags with `tempo node --help` before enabling (an unknown flag stops the node).',
    ],
  },
  fluent: {
    flags: { ...RETH_RPC, 'http.api': 'eth,net,web3,rpc', 'ws.api': 'eth,net,web3,rpc' },
    notes: [...RETH_NOTES, 'Fluent runs a reth fork: check the cache flags with `fluent node --help` before enabling.'],
  },
  berachain: {
    flags: { ...RETH_RPC, 'http.api': 'eth,net,web3,rpc', 'ws.api': 'eth,net,web3,rpc' },
    notes: [...RETH_NOTES, 'bera-reth only (reth v1.11.4); beacond is not overridden.'],
  },
  stellar: {
    // stellar-rpc v29.0.1 option table (flags beat the generated stellar-rpc.toml). Judgement values: measure first.
    flags: {
      // simulateTransaction is the CPU hog: size the pool to the cores you give the node, bound the waiting line
      'preflight-worker-count': '16',
      'preflight-worker-queue-size': '64',
      // a smaller getEvents page bounds response size / DB scan time per call (stock 10000)
      'max-events-limit': '5000',
      // captive-core query server threads for getLedgerEntries (stock = CPU count)
      'stellar-captive-core-http-query-thread-pool-size': '8',
    },
    notes: [
      'Values are for a 16-core / 32 GB host; the generated file already runs 8 / 32 on the 8-core profile.',
      'ENDPOINT is public with no TLS, auth or rate limit: put a rate-limiting reverse proxy with a response cache in front.',
      'Keep the admin port (metrics + pprof) and the captive-core query port on 127.0.0.1 — they have no auth.',
    ],
  },
  aptos: {
    prefix: '',
    // aptos-node reads fullnode.yaml: these keys are written into it on the next Start (allowlisted, validated).
    flags: {
      'api.runtime_worker_multiplier': '4',
      'api.wait_by_hash_max_active_connections': '500',
      'p2p.max_outbound_connections': '10',
    },
    notes: [
      'Checked against aptos-core aptos-node-v1.49.x config structs (unknown fields are rejected there).',
      'The REST API listens on 0.0.0.0 without limits or TLS: put a rate-limiting proxy in front.',
    ],
  },
  xrpl: {
    prefix: '',
    flags: {
      // stops publishing peer/server info on the peer port
      crawl: '0',
      // peers do not add RPC capacity; 100 adds relay CPU and bandwidth
      peers_max: '50',
    },
    notes: [
      'xrpld.cfg single-line sections (checked against rippled 3.4.1). node_size is left alone: set it after the first sync.',
    ],
  },
}

// Substrate relay chain (polkadot). Flags and defaults read from sc-cli at polkadot-stable2609 (rpc_params.rs / import_params.rs).
// The unit already runs rpc-max-connections 1000 (vendor default 100; docs.polkadot.com's RPC recipe uses 1000).
PROD_PRESETS.polkadot = {
  flags: {
    // also the size of the RPC blocking-thread pool (judgement: 2000 for a public endpoint behind a proxy)
    'rpc-max-connections': '2000',
    // default 64 messages per connection; a slow WS consumer is dropped when its buffer fills
    'rpc-message-buffer-capacity-per-connection': '256',
    // default 1 GiB: the biggest read-path cache (judgement: 4 GiB on a 32 GB host)
    'trie-cache-size': '4294967296',
  },
  notes: [
    '--rpc-methods stays safe and Prometheus stays on 127.0.0.1: neither is changed.',
    'A pruned node keeps 256 blocks of state: queries older than ~25 min fail. Run archive for history.',
    'The optional REST API shares these limits: it holds one WS connection to the node and fans every request out to it.',
  ],
}
// fuel-core v0.48.3 clap definitions: graphql threads default 2, concurrent queries 1024, database cache bytes.
PROD_PRESETS.fuel = {
  flags: {
    'graphql-number-of-threads': '8',
    'graphql-max-concurrent-queries': '4096',
    // bytes (judgement: 8 GiB on a 16+ GB host)
    'max-database-cache-size': '8589934592',
  },
  notes: [
    '--debug is never passed and the gRPC --rpc-port stays off. /v1/metrics is on the public GraphQL port: block it at the proxy.',
    'The relayer pages eth_getLogs against the Ethereum RPC: a node you control is the real answer, public RPCs rate-limit.',
  ],
}
PROD_PRESETS.aleo = {
  notes: [
    'Start already runs rest-rps 1000 (vendor default 10 per IP is unusable behind a proxy where every client shares one IP).',
    'JWT stays on (never --nojwt): it guards /db_backup, /node/address and the mapping routes. Metrics are on 127.0.0.1.',
  ],
}
PROD_PRESETS.near = {
  notes: [
    'Start applies rpc.enable_debug_rpc=false and view_client_threads=16 (vendor default 4; judgement for a public node) to config.json on every run.',
    'Other config.json keys are yours (edit on the host, then restart); gc_num_epochs_to_keep stays 5 (disk grows with it).',
  ],
}
PROD_PRESETS.stacks = {
  notes: [
    'Start applies [connection_options] max_http_clients = 3000 (vendor default 1000; judgement) and keeps auth_token unset.',
    'The Bitcoin RPC password is in stacks.toml (0600) only. Prometheus is on 127.0.0.1.',
  ],
}

/** Bitcoin-family / java-tron config keys for the Node config document editor. */
const BTC_CONF = { disablewallet: '1', dbcache: '2048' }
PROD_PRESETS.bitcoin = { conf: { ...BTC_CONF }, notes: ['dbcache 2048 MiB (needs RAM); the wallet is not used by an RPC node.'] }
PROD_PRESETS.bch = { conf: { ...BTC_CONF }, notes: ['dbcache 2048 MiB (needs RAM); the wallet is not used by an RPC node.'] }
PROD_PRESETS.dash = { conf: { ...BTC_CONF }, notes: ['dbcache 2048 MiB (needs RAM); the wallet is not used by an RPC node.'] }
PROD_PRESETS.ltc = {
  conf: { ...BTC_CONF, blockfilterindex: '0', peerblockfilters: '0' },
  notes: ['Litecoin turns the block filter index on by default (disk + IBD time); peerblockfilters must go off with it.'],
}
PROD_PRESETS.zcash = {
  conf: { ...BTC_CONF, rpcthreads: '64', rpcworkqueue: '4096' },
  notes: ['zcashd defaults are 4 RPC threads / queue 16; the toolkit template does not set them.'],
}
PROD_PRESETS.tron = {
  conf: { 'vm.supportConstant': 'true', 'node.maxHttpConnectNumber': '1000' },
  notes: ['supportConstant=false rejects view / constant contract calls; maxHttpConnectNumber 50 is a hard connection ceiling.'],
}

export function prodPresetFor(network: string): ProdPreset | null {
  return PROD_PRESETS[(network || '').toLowerCase()] ?? null
}
