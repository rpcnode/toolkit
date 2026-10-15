import { Radio, Stack, Text, TextInput, UnstyledButton } from '@mantine/core'
import { isOpstackNetwork } from '../lib/opstack'

export type InstallOptionChoice = {
  id: string
  title: string
  hint?: string
  snapshot_url?: string
  /** Choosing this also sets these other options (e.g. the history indexer needs the archive node type). */
  sets?: Record<string, string>
  /** The choice cannot be picked while another option has the given value (e.g. pruned while the history indexer is chosen). */
  disabledWhen?: Record<string, string>
}

export type InstallOptionGroup = {
  id: string
  label: string
  hint?: string
  default?: string
  choices: InstallOptionChoice[]
  /** Optional free-text value saved next to the radio choice (install_options[textInput.id]). */
  textInput?: {
    id: string
    label: string
    placeholder?: string
    hint?: string
    /** Show the field only while this choice of the group is selected. */
    whenChoice?: string
  }
}

export const TRON_MAINNET_SNAPSHOT_OPTIONS: InstallOptionGroup = {
  id: 'snapshot',
  label: 'Snapshot',
  hint: 'Official TRON FullNode mirrors. Internal transactions and historical balances are different snapshots.',
  default: 'standard',
  choices: [
    {
      id: 'standard',
      title: 'Standard · no internal txs',
      hint: 'US Virginia. ~2.9 TB. Default. AML will not see historical contract internals.',
    },
    {
      id: 'internal_tx',
      title: 'Internal transactions',
      hint: 'Singapore. ~3.1 TB. For AML that must see contract internal calls (gettransactioninfobyid).',
    },
    {
      id: 'balance_history',
      title: 'Historical TRX balances',
      hint: 'US. ~3.6 TB. getaccountbalance at any past block. No historical internal txs.',
    },
  ],
}

export function parseInstallOptionGroups(raw: unknown): InstallOptionGroup[] {
  if (!Array.isArray(raw)) return []
  const out: InstallOptionGroup[] = []
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue
    const g = item as Record<string, unknown>
    const id = String(g.id || '').trim()
    const choicesRaw = Array.isArray(g.choices) ? g.choices : []
    const choices: InstallOptionChoice[] = []
    for (const c of choicesRaw) {
      if (!c || typeof c !== 'object') continue
      const ch = c as Record<string, unknown>
      const cid = String(ch.id || '').trim()
      if (!cid) continue
      choices.push({
        id: cid,
        title: String(ch.title || cid),
        hint: ch.hint ? String(ch.hint) : undefined,
        snapshot_url: ch.snapshot_url ? String(ch.snapshot_url) : undefined,
      })
    }
    if (!id || !choices.length) continue
    out.push({
      id,
      label: String(g.label || id),
      hint: g.hint ? String(g.hint) : undefined,
      default: g.default ? String(g.default) : undefined,
      choices,
    })
  }
  return out
}

export const BSC_SNAPSHOT_OPTIONS: InstallOptionGroup = {
  id: 'snapshot',
  label: 'Snapshot',
  hint: 'Official bnb-chain/bsc-snapshots (geth PBSS). Genesis full sync of 100M+ blocks is not practical.',
  default: 'pruned',
  choices: [
    {
      id: 'pruned',
      title: 'Pruned · pruneancient',
      hint: 'Official pruneancient (~1.9 TB mainnet / ~180 GB testnet). Matches gcmode=full. Default.',
    },
    {
      id: 'full',
      title: 'Full history',
      hint: 'Official full ancient (~6.6 TB mainnet / ~440 GB testnet). Needs ~8 TB free. Not the 2 TB product disk.',
    },
  ],
}

export const BSC_SNAPSHOT_MODE_OPTIONS: InstallOptionGroup = {
  id: 'snapshot_mode',
  label: 'Download mode',
  hint: 'How the official bnb-chain/bsc-snapshots parts reach the datadir.',
  default: 'official',
  choices: [
    {
      id: 'official',
      title: 'Official · fetch-snapshot.sh',
      hint: 'Default. aria2 downloads each part to the snapshots disk, checks MD5, then extracts. Needs room for the largest part (~1.9 TB mainnet base) on the snapshots disk.',
    },
    {
      id: 'stream',
      title: 'Stream · HTTP → tar',
      hint: 'Each part is piped straight into the datadir (lz4 + MD5 on the fly), nothing staged on the snapshots disk. Drops resume via HTTP Range; an agent restart re-streams only the part in flight.',
    },
  ],
}

export const BASE_SNAPSHOT_OPTIONS: InstallOptionGroup = {
  id: 'snapshot',
  label: 'Snapshot',
  hint: 'Official V2 snapshots via base-reth-node download. A from-scratch sync takes days.',
  default: 'archive',
  choices: [
    {
      id: 'archive',
      title: 'Archive · full history',
      hint: 'Everything available, no pruning (~3711 GiB mainnet / ~956 GiB sepolia). Matches the archive unit. Default.',
    },
    {
      id: 'full',
      title: 'Full · reth full-node preset',
      hint: "reth's --full preset (~3145 GiB mainnet): state, headers and only the last 10064 blocks (~5-6 h on Base).",
    },
    {
      id: 'minimal',
      title: 'Minimal · state + headers',
      hint: 'Smallest set needed to boot (~677 GiB mainnet / ~283 GiB sepolia). No historical data.',
    },
  ],
}

export const XRPL_HISTORY_OPTIONS: InstallOptionGroup = {
  id: 'xrpl_history',
  label: 'History to install',
  hint: 'How much ledger history xrpld will keep after Install. Full history has no public snapshot.',
  default: 'weeks',
  choices: [
    { id: 'stock', title: 'Stock · ~2 hours', hint: '2 000 ledgers — default xrpld window. Smallest disk, no archive RPC.' },
    { id: 'day', title: '1 day', hint: '25 000 ledgers. Typical public RPC day window.' },
    { id: 'weeks', title: '2 weeks', hint: '300 000 ledgers. Default for a new install. Disk ×2 of the window.' },
    { id: 'full', title: 'Full history', hint: 'Genesis → tip (ledger 32 570 on mainnet). No snapshot. Official archive ~39 TiB — not a VPS.' },
  ],
}

export const SOLANA_NODE_OPTIONS: InstallOptionGroup = {
  id: 'node',
  label: 'Node type',
  hint: 'Full RPC keeps a bounded ledger. Archive drops --limit-ledger-size and grows toward full history (hundreds of TiB on mainnet).',
  default: 'full',
  choices: [
    {
      id: 'full',
      title: 'Full RPC · bounded ledger',
      hint: 'Product default. Cluster snapshot + --limit-ledger-size. Live RPC, not genesis→tip history.',
    },
    {
      id: 'archive',
      title: 'Archive · no ledger limit',
      hint: 'Omits --limit-ledger-size. Same cluster snapshot bootstrap; disk plan ~400 TiB mainnet. Not BigTable.',
    },
  ],
}

export const ETHEREUM_NODE_OPTIONS: InstallOptionGroup = {
  id: 'node',
  label: 'Node type',
  hint: 'Geth --gcmode. Full = snap+gcmode full. Archive = full sync + gcmode archive (multi-TiB).',
  default: 'full',
  choices: [
    {
      id: 'full',
      title: 'Full · snap + gcmode full',
      hint: 'Product default. Not archive RPC.',
    },
    {
      id: 'archive',
      title: 'Archive · gcmode archive',
      hint: 'Geth --syncmode full --gcmode archive. Mainnet ~14 TiB floor.',
    },
  ],
}

export const ETC_NODE_OPTIONS: InstallOptionGroup = {
  id: 'node',
  label: 'Node type',
  hint: 'Core-Geth mode. Full = snap sync (about an hour) + gcmode full. Archive = full sync from genesis + gcmode archive (days to weeks).',
  default: 'full',
  choices: [
    {
      id: 'full',
      title: 'Full · snap + gcmode full',
      hint: 'Product default. About 51 GiB on mainnet (vendor measurement, 2026-09). State of the last 100000 blocks (about a week); the window can be changed below.',
    },
    {
      id: 'archive',
      title: 'Archive · gcmode archive',
      hint: 'Syncs from genesis and keeps every state. Not measured by the vendor; plan about 1 TiB on mainnet.',
    },
  ],
}

export const STELLAR_HISTORY_OPTIONS: InstallOptionGroup = {
  id: 'history',
  label: 'History window',
  hint: 'Mainnet week / month is backfilled from the public AWS ledger data lake on the first start. Forward-only never prunes and grows ~40 GB per day.',
  default: 'week',
  choices: [
    {
      id: 'week',
      title: '1 week · stock (120 960 ledgers)',
      hint: 'Product default. ~350 GB. Mainnet backfills 7 days (~24 GB download) before the RPC is healthy.',
    },
    {
      id: 'month',
      title: '1 month (518 400 ledgers)',
      hint: '~1.3 TB. Mainnet backfills 30 days (~100 GB download).',
    },
    {
      id: 'forward',
      title: 'Forward-only · never prune',
      hint: 'Keeps everything from the first start (no backfill). Unbounded disk.',
    },
  ],
}

export const AVALANCHE_NODE_OPTIONS: InstallOptionGroup = {
  id: 'node',
  label: 'Node type',
  hint: 'C-Chain mode of avalanchego. Full prunes and bootstraps with state sync; archive keeps every state and replays from genesis.',
  default: 'full',
  choices: [
    {
      id: 'full',
      title: 'Full · pruned + state sync',
      hint: 'Product default. Hours to sync, ~1.5 TiB mainnet. Not archive RPC: old state is not queryable.',
    },
    {
      id: 'archive',
      title: 'Archive · pruning off',
      hint: 'pruning-enabled=false, no state sync: replays the C-Chain from genesis (days to weeks, multi-TiB).',
    },
  ],
}

export const POLYGON_NODE_OPTIONS: InstallOptionGroup = {
  id: 'node',
  label: 'Node type',
  hint: 'Bor profile. Full = sentry. Archive = archive config (multi-TiB mainnet).',
  default: 'full',
  choices: [
    {
      id: 'full',
      title: 'Full · sentry',
      hint: 'Product default. Live tip RPC, not deep history.',
    },
    {
      id: 'archive',
      title: 'Archive · bor archive',
      hint: 'Official archive profile. Mainnet ~16 TiB floor.',
    },
  ],
}

export const ARB_SNAPSHOT_OPTIONS: InstallOptionGroup = {
  id: 'snapshot',
  label: 'Snapshot',
  hint: 'Nitro init. Pruned = --init.latest=pruned. Archive = PathDB archive-path + caching.archive.',
  default: 'pruned',
  choices: [
    {
      id: 'pruned',
      title: 'Pruned · full node',
      hint: 'Product default. Live tip, not deep history.',
    },
    {
      id: 'archive',
      title: 'Archive · PathDB',
      hint: 'Official archive-path (~3.7 TB One). Not Classic pre-Nitro.',
    },
  ],
}

export const OPTIMISM_NODE_OPTIONS: InstallOptionGroup = {
  id: 'node',
  label: 'Node type',
  hint: 'op-reth + op-node, built on the host. The OP Labs snapshot is restored on the first start (mandatory on OP Mainnet). op-geth is end-of-support and not offered.',
  default: 'full',
  choices: [
    {
      id: 'full',
      title: 'Full · pruned state',
      hint: 'Product default. Full-storage-v2 snapshot (~0.7 TiB mainnet), state and receipts pruned to ~30 days, block bodies kept.',
    },
    {
      id: 'archive',
      title: 'Archive · no pruning',
      hint: 'Archive-storage-v2 snapshot (several TiB mainnet), no prune flags: historical eth_call and debug_trace*.',
    },
  ],
}

/** How the datadir of an OP Stack family node starts (chains/opstack: the run script restores it, there is no snapshot step). */
type OpstackSnapshotSource = 'alchemy' | 'gelato' | 's3' | 'none'

function opstackSnapshotSource(net: string, env: string): OpstackSnapshotSource {
  if (net === 'unichain' || net === 'ink') return 'alchemy'
  if (net === 'soneium') return env === 'mainnet' ? 'alchemy' : 'none'
  if (net === 'worldchain') return 's3'
  if (net === 'lisk') return 'gelato'
  return 'none'
}

export const OPSTACK_NODE_OPTIONS: InstallOptionGroup = {
  id: 'node',
  label: 'Node type',
  hint: 'op-reth (World Chain: world-chain) + op-node. op-geth is end-of-support and not offered.',
  default: 'full',
  choices: [
    {
      id: 'full',
      title: 'Full · pruned state',
      hint: 'Product default. State and receipts pruned to ~30 days, block bodies kept. Published snapshots are archive-style datadirs, so plan archive-size disk until the pruner has run.',
    },
    {
      id: 'archive',
      title: 'Archive · no pruning',
      hint: 'No prune flags: historical eth_call at any block.',
    },
  ],
}

export function opstackRestoreOptions(net: string, env: string): InstallOptionGroup {
  const source = opstackSnapshotSource(net, env)
  const noSource = source === 'none'
  const builtIn: Record<OpstackSnapshotSource, string> = {
    alchemy: 'THIRD-PARTY snapshot from Alchemy (free, no SLA, no checksum).',
    gelato: 'Official Lisk snapshot (Gelato ChainSnap, sha256-checked).',
    s3: 'Official World Chain snapshot (S3, rewritten daily, no checksum published).',
    none: 'No public snapshot exists for this network.',
  }
  return {
    id: 'restore',
    label: 'Initial data',
    hint: noSource
      ? `${builtIn.none} Paste a datadir archive URL below to start from a snapshot; without one the node derives the chain from L1, which takes WEEKS.`
      : `${builtIn[source]} Restored once on the first start; a URL below overrides it.`,
    // Unichain syncs from scratch in hours and its snapshot is third-party: start without one (the backend default agrees).
    default: net === 'unichain' ? 'none' : 'snapshot',
    choices: [
      {
        id: 'snapshot',
        title: noSource ? 'Snapshot URL · you supply the archive' : 'Restore a snapshot',
        hint: noSource
          ? 'Needs a URL below (.tar / .tar.gz / .tar.lz4 / .tar.zst of the reth datadir). Empty = derive from L1 (weeks).'
          : 'Download and extract the datadir first; the node then only has to catch up.',
      },
      {
        id: 'none',
        title: 'Sync without a snapshot',
        hint: noSource
          ? 'Derive the chain from L1 (weeks).'
          : 'No download: slow, but nothing from a third party is trusted.',
      },
    ],
    textInput: {
      id: 'snapshot_url',
      label: noSource ? 'Snapshot URL' : 'Snapshot URL (optional)',
      placeholder: 'https://…/datadir.tar.lz4',
      hint: 'http(s) only, ending in .tar, .tar.gz, .tar.lz4 or .tar.zst. A signed URL is kept in a file readable by root only.',
    },
  }
}

export const ROBINHOOD_SNAPSHOT_OPTIONS: InstallOptionGroup = {
  id: 'snapshot',
  label: 'Snapshot',
  hint: 'Nitro init from robinhood-snapshots.offchainlabs.com. Pruned = --init.latest=pruned. Archive = --init.latest=archive + caching.archive (PathDB on mainnet).',
  default: 'pruned',
  choices: [
    {
      id: 'pruned',
      title: 'Pruned · full node',
      hint: 'Product default. Live tip, not deep history. ~0.9 TiB mainnet, ~0.3 TiB testnet snapshot.',
    },
    {
      id: 'archive',
      title: 'Archive',
      hint: 'Official archive snapshot (~1.3 TiB mainnet PathDB, ~4.3 TiB testnet).',
    },
  ],
}

export const TON_HISTORY_OPTIONS: InstallOptionGroup = {
  id: 'history',
  label: 'History',
  hint: 'MyTonCtrl liteserver dump (~30d) vs archive (≥12 TiB).',
  default: 'dump',
  choices: [
    {
      id: 'dump',
      title: 'Dump · ~30 days',
      hint: 'install.sh -m liteserver -d. Product default.',
    },
    {
      id: 'archive',
      title: 'Archive · full history',
      hint: 'install.sh -m liteserver --archive. Official ≥12–20 TiB.',
    },
  ],
}

export const APTOS_BOOTSTRAP_OPTIONS: InstallOptionGroup = {
  id: 'bootstrap',
  label: 'Sync mode',
  hint: 'How the node gets its first state. Mainnet public peers have pruned history, so a genesis sync cannot finish there.',
  default: 'fast',
  choices: [
    {
      id: 'fast',
      title: 'Fast · latest state, pruned',
      hint: 'DownloadLatestStates: hours, hundreds of GB. Ledger history older than ~150M versions is pruned.',
    },
    {
      id: 'genesis',
      title: 'Genesis · full history',
      hint: 'ExecuteOrApplyFromGenesis with the pruners off. Practical on testnet only; mainnet needs archival seed peers and 8+ TiB.',
    },
  ],
}

export const CARDANO_INDEXER_OPTIONS: InstallOptionGroup = {
  id: 'indexer',
  label: 'Indexer',
  hint: 'Optional companions next to cardano-node, installed on the next Start. cardano-node itself has no RPC: Ogmios is its JSON-RPC / WebSocket API. Never expose either unauthenticated.',
  default: 'ogmios',
  choices: [
    {
      id: 'none',
      title: 'None · node only',
      hint: 'P2P relay only, no query API.',
    },
    {
      id: 'ogmios',
      title: 'Ogmios · JSON-RPC / WebSocket',
      hint: 'Default. Listens on 127.0.0.1 only (put an authenticating, rate-limiting proxy in front; it also submits transactions). Small: no disk, a few hundred MB RAM.',
    },
    {
      id: 'dbsync',
      title: 'db-sync · PostgreSQL index',
      hint: 'cardano-db-sync writes the whole chain into the host PostgreSQL (existing server reused; own role, database and tablespace on the Indexer disk). Mainnet ~440 GB DB (plan 700 GB+ NVMe), ~21 GB RAM next to the node (64 GB host), first sync takes days. Linux x86_64 only.',
    },
    {
      id: 'both',
      title: 'Ogmios + db-sync',
      hint: 'Both of the above. Needs the 64 GB / 700 GB+ mainnet host described for db-sync.',
    },
  ],
}

export const NETHERMIND_NODE_OPTIONS: InstallOptionGroup = {
  id: 'node',
  label: 'Node type',
  hint: 'Nethermind config. Full = the shipped config (snap sync). Archive = the *_archive config (pruning off, history kept; multi-TiB).',
  default: 'full',
  choices: [
    {
      id: 'full',
      title: 'Full · snap sync',
      hint: 'Product default. Current state only, not archive RPC.',
    },
    {
      id: 'archive',
      title: 'Archive · pruning off',
      hint: 'Nethermind <chain>_archive config: full history, historical eth_call. Several TiB on Gnosis.',
    },
  ],
}

export const FLARE_NODE_OPTIONS: InstallOptionGroup = {
  id: 'node',
  label: 'Node type',
  hint: 'C-Chain mode of go-flare. Full prunes; archive keeps every state (docs: ~5.7 TB Flare, ~11.2 TB Songbird).',
  default: 'full',
  choices: [
    {
      id: 'full',
      title: 'Full · pruned',
      hint: 'Product default. About 1 TB on Flare mainnet (docs table, mapping to networks is positional).',
    },
    {
      id: 'archive',
      title: 'Archive · pruning off',
      hint: 'pruning-enabled=false and state sync off: replays the C-Chain from genesis.',
    },
  ],
}

export const SONIC_SNAPSHOT_OPTIONS: InstallOptionGroup = {
  id: 'snapshot',
  label: 'Genesis flavour',
  hint: 'A Sonic node is created by importing a genesis file with sonictool (hours). The RPC is not available until the import has finished.',
  default: 'pruned',
  choices: [
    {
      id: 'pruned',
      title: 'Pruned · latest-sonic-pruned.g',
      hint: '6.5 GB download on mainnet (368 MB testnet). History starts at the genesis epoch. Default.',
    },
    {
      id: 'archive',
      title: 'Archive · latest-sonic-archive.g',
      hint: '181 GB download on mainnet (15 GB testnet), about 3 TB of local NVMe afterwards.',
    },
  ],
}

export const MONAD_SNAPSHOT_OPTIONS: InstallOptionGroup = {
  id: 'snapshot',
  label: 'TrieDB snapshot',
  hint: 'A Monad full node starts from the Monad Foundation TrieDB snapshot (8.7 GB mainnet, 3.1 GB testnet) imported into a dedicated NVMe partition (2 TB recommended, 512-byte sectors, bare metal, 16+ CPUs).',
  default: 'full',
  choices: [
    {
      id: 'full',
      title: 'Latest snapshot · bucket.monadinfra.com',
      hint: 'Downloaded, checked (sha256) and imported with monad-cli. The Snapshot step ERASES the TrieDB partition: it never touches a device that is mounted or holds a filesystem / partition table.',
    },
  ],
  textInput: {
    id: 'triedb_device',
    label: 'TrieDB block device (required)',
    placeholder: '/dev/disk/by-partuuid/…',
    hint: 'An empty partition of a dedicated NVMe drive, created beforehand (parted mklabel gpt; mkpart triedb 0% 100%). Use a stable path (by-partuuid / by-id), not /dev/nvmeXnY.',
  },
}

export const MONAD_PUBLIC_IP_OPTIONS: InstallOptionGroup = {
  id: 'ip',
  label: 'Public address',
  hint: 'The node advertises its public IPv4 in a signed name record (re-signed automatically when it changes).',
  default: 'auto',
  choices: [
    {
      id: 'auto',
      title: 'Detect automatically',
      hint: 'Asks api.ipify.org / ifconfig.me from the host. Fill the field below to pin an address instead (NAT, several interfaces).',
    },
  ],
  textInput: {
    id: 'public_ip',
    label: 'Public IPv4 (optional)',
    placeholder: '203.0.113.10',
  },
}

export const TEMPO_SNAPSHOT_OPTIONS: InstallOptionGroup = {
  id: 'snapshot',
  label: 'Snapshot',
  hint: 'tempo download restores execution and consensus data of one snapshot. Tempo needs local NVMe (no network volumes).',
  default: 'archive',
  choices: [
    {
      id: 'archive',
      title: 'Archive · all components',
      hint: 'Mainnet ~20 GB download / ~54 GB on disk. For RPC providers and indexers. Default.',
    },
    {
      id: 'full',
      title: 'Full · node set',
      hint: 'tempo download --full.',
    },
    {
      id: 'minimal',
      title: 'Minimal',
      hint: 'tempo download --minimal: ~10 GB on mainnet, no old history and no historical RPC.',
    },
  ],
}

export const BERACHAIN_SNAPSHOT_OPTIONS: InstallOptionGroup = {
  id: 'snapshot',
  label: 'Node type / snapshot',
  hint: 'Official snapshot pair (bera-reth + beacon-kit of the same block). The choice also decides bera-reth --full.',
  default: 'pruned',
  choices: [
    {
      id: 'pruned',
      title: 'Pruned · reth --full',
      hint: 'Product default. ~54 GB reth + ~30 MB beacon-kit download.',
    },
    {
      id: 'archive',
      title: 'Archive',
      hint: '~1.2 TB reth + ~336 GB beacon-kit download; needs ~2.7 TB free.',
    },
  ],
}

export const POLKADOT_SNAPSHOT_OPTIONS: InstallOptionGroup = {
  id: 'snapshot',
  label: 'Node type',
  hint: 'Pruning is fixed on the first start of a database: pruned <-> archive later means a wipe. The Parity snapshot restored on the Snapshot step is the one chosen here. The history indexer needs Archive.',
  default: 'pruned',
  choices: [
    {
      id: 'pruned',
      title: 'Pruned · paritydb, warp sync',
      hint: 'Product default. State and blocks of the last 256 blocks (~22 GB snapshot on Polkadot); warp sync also works without a snapshot. Older state is not queryable. Not available with the history indexer.',
      disabledWhen: { indexer: 'history' },
    },
    {
      id: 'archive',
      title: 'Archive · full history',
      hint: 'Every block and state. Polkadot ~4.2 TB (rocksdb), Paseo ~92 GB (rocksdb), Westend ~1.1 TB (paritydb); growth ~1 TB/yr on Polkadot.',
    },
  ],
}

export const POLKADOT_INDEXER_OPTIONS: InstallOptionGroup = {
  id: 'indexer',
  label: 'Indexer',
  hint: 'Optional companion next to the node. Both options listen next to the node RPC on their own catalog port; put a rate-limiting reverse proxy in front.',
  default: 'none',
  choices: [
    { id: 'none', title: 'None', hint: 'Only the node RPC.' },
    {
      id: 'rest-api',
      title: 'REST API · polkadot-rest-api',
      hint: 'Parity polkadot-rest-api (the successor of substrate-api-sidecar): blocks, extrinsics, accounts, fees, transaction submit over this node. Stateless, no database; history beyond 256 blocks needs the archive node. Built from source on the host (Rust, long first start). Listens on 0.0.0.0 like the RPC; POST /v1/transaction is exposed.',
    },
    {
      id: 'history',
      title: 'History · PostgreSQL + GraphQL',
      hint: 'Blocks, extrinsics, events and balance transfers of this node kept in the host PostgreSQL (own role, database and tablespace on the Indexer disk) and served as GraphQL (Subsquid, Node.js 24 downloaded under the indexer dir). Needs the ARCHIVE node type (selecting it switches the node type). The GraphQL API listens on all interfaces without authentication or rate limit: firewall it or put a proxy in front. By default it indexes from the current head (recent history); a genesis backfill goes through the node RPC and takes a long time. Linux only.',
      sets: { snapshot: 'archive' },
    },
  ],
  textInput: {
    id: 'indexer_start_block',
    label: 'Start block (history indexer)',
    placeholder: 'genesis · head · 33000000',
    hint: 'First block of the first run: head (from the finalized head now, the default), genesis (everything, a very long backfill) or a block number. Applies to a first run only; Westend cannot start below block 7752190.',
    whenChoice: 'history',
  },
}

export function fallbackInstallGroups(network?: string, env?: string): InstallOptionGroup[] {
  const net = (network || '').toLowerCase()
  const e = (env || '').toLowerCase()
  if (net === 'tron' && e === 'mainnet') {
    return [TRON_MAINNET_SNAPSHOT_OPTIONS]
  }
  if (net === 'bsc' && (e === 'mainnet' || e === 'testnet')) {
    return [BSC_SNAPSHOT_OPTIONS, BSC_SNAPSHOT_MODE_OPTIONS]
  }
  if (net === 'base' && (e === 'mainnet' || e === 'sepolia')) {
    return [BASE_SNAPSHOT_OPTIONS]
  }
  if (net === 'xrpl' && (e === 'mainnet' || e === 'testnet')) {
    return [XRPL_HISTORY_OPTIONS]
  }
  if (net === 'solana' && (e === 'mainnet' || e === 'testnet' || e === 'devnet')) {
    return [SOLANA_NODE_OPTIONS]
  }
  if (net === 'ethereum' && (e === 'mainnet' || e === 'sepolia' || e === 'hoodi')) {
    return [ETHEREUM_NODE_OPTIONS]
  }
  if (net === 'stellar' && (e === 'mainnet' || e === 'testnet')) {
    return [STELLAR_HISTORY_OPTIONS]
  }
  if (net === 'etc' && (e === 'mainnet' || e === 'mordor')) {
    return [ETC_NODE_OPTIONS]
  }
  if (net === 'avalanche' && (e === 'mainnet' || e === 'fuji')) {
    return [AVALANCHE_NODE_OPTIONS]
  }
  if (net === 'polygon' && (e === 'mainnet' || e === 'amoy')) {
    return [POLYGON_NODE_OPTIONS]
  }
  if (net === 'arb' && (e === 'mainnet' || e === 'sepolia')) {
    return [ARB_SNAPSHOT_OPTIONS]
  }
  if (net === 'optimism' && (e === 'mainnet' || e === 'sepolia')) {
    return [OPTIMISM_NODE_OPTIONS]
  }
  if (isOpstackNetwork(net) && (e === 'mainnet' || e === 'sepolia')) {
    return [OPSTACK_NODE_OPTIONS, opstackRestoreOptions(net, e)]
  }
  if (net === 'robinhood' && (e === 'mainnet' || e === 'testnet')) {
    return [ROBINHOOD_SNAPSHOT_OPTIONS]
  }
  if (net === 'ton' && (e === 'mainnet' || e === 'testnet')) {
    return [TON_HISTORY_OPTIONS]
  }
  if (net === 'aptos' && (e === 'mainnet' || e === 'testnet')) {
    return [APTOS_BOOTSTRAP_OPTIONS]
  }
  if (net === 'polkadot' && (e === 'mainnet' || e === 'paseo' || e === 'westend')) {
    return [POLKADOT_SNAPSHOT_OPTIONS, POLKADOT_INDEXER_OPTIONS]
  }
  if ((net === 'gnosis' && (e === 'mainnet' || e === 'chiado')) || (net === 'joc' && (e === 'mainnet' || e === 'testnet'))) {
    return [NETHERMIND_NODE_OPTIONS]
  }
  if (net === 'flare' && (e === 'flare' || e === 'songbird' || e === 'coston2')) {
    return [FLARE_NODE_OPTIONS]
  }
  if (net === 'sonic' && (e === 'mainnet' || e === 'testnet')) {
    return [SONIC_SNAPSHOT_OPTIONS]
  }
  if (net === 'monad' && (e === 'mainnet' || e === 'testnet')) {
    return [MONAD_SNAPSHOT_OPTIONS, MONAD_PUBLIC_IP_OPTIONS]
  }
  if (net === 'tempo' && (e === 'mainnet' || e === 'moderato')) {
    return [TEMPO_SNAPSHOT_OPTIONS]
  }
  if (net === 'berachain' && e === 'mainnet') {
    return [BERACHAIN_SNAPSHOT_OPTIONS]
  }
  if (net === 'cardano' && (e === 'mainnet' || e === 'preprod' || e === 'preview')) {
    return [CARDANO_INDEXER_OPTIONS]
  }
  return []
}

export function installOptionLabel(groups: InstallOptionGroup[], selected: Record<string, string>): string {
  const parts: string[] = []
  for (const g of groups) {
    const id = selected[g.id] || g.default || ''
    const ch = g.choices.find((c) => c.id === id)
    if (ch) parts.push(ch.title.split('·')[0].trim())
  }
  return parts.join(' · ')
}

/** The value of option [id] as the user sees it: what was chosen, else the group's default. */
function optionValue(groups: InstallOptionGroup[], value: Record<string, string>, id: string): string {
  const g = groups.find((x) => x.id === id)
  return value[id] || g?.default || g?.choices[0]?.id || ''
}

/** True while another option's value rules this choice out (`disabledWhen`). */
export function choiceBlocked(
  choice: InstallOptionChoice,
  value: Record<string, string>,
  groups: InstallOptionGroup[],
): boolean {
  if (!choice.disabledWhen) return false
  return Object.entries(choice.disabledWhen).some(([id, want]) => optionValue(groups, value, id) === want)
}

/** Picks [choiceId] in group [groupId] and applies what the choice sets elsewhere (`sets`). */
export function chooseOption(
  groups: InstallOptionGroup[],
  value: Record<string, string>,
  groupId: string,
  choiceId: string,
): Record<string, string> {
  const choice = groups.find((g) => g.id === groupId)?.choices.find((c) => c.id === choiceId)
  return { ...value, ...(choice?.sets ?? {}), [groupId]: choiceId }
}

export function InstallOptionsPicker({
  groups,
  value,
  onChange,
  disabled,
}: {
  groups: InstallOptionGroup[]
  value: Record<string, string>
  onChange: (next: Record<string, string>) => void
  disabled?: boolean
}) {
  if (!groups.length) return null

  return (
    <Stack gap="md">
      {groups.map((g) => {
        const current = value[g.id] || g.default || g.choices[0]?.id || ''
        return (
          <Stack key={g.id} gap="xs">
            <Text size="sm" fw={600}>
              {g.label}
            </Text>
            {g.hint ? (
              <Text size="xs" c="dimmed">
                {g.hint}
              </Text>
            ) : null}
            <Radio.Group
              value={current}
              onChange={(v) => onChange(chooseOption(groups, value, g.id, v))}
            >
              <Stack gap={8}>
                {g.choices.map((m) => {
                  const selected = current === m.id
                  const blocked = choiceBlocked(m, value, groups)
                  return (
                    <UnstyledButton
                      key={m.id}
                      disabled={disabled || blocked}
                      onClick={() => onChange(chooseOption(groups, value, g.id, m.id))}
                      p="sm"
                      style={{
                        borderRadius: 8,
                        border: `1px solid ${
                          selected ? 'var(--mantine-color-teal-6)' : 'var(--mantine-color-dark-4)'
                        }`,
                        background: selected ? 'var(--mantine-color-dark-6)' : 'transparent',
                        textAlign: 'left',
                      }}
                    >
                      <Radio
                        value={m.id}
                        label={
                          <Stack gap={2}>
                            <Text size="sm" fw={600}>
                              {m.title}
                            </Text>
                            {m.hint ? (
                              <Text size="xs" c="dimmed" style={{ whiteSpace: 'normal' }}>
                                {m.hint}
                              </Text>
                            ) : null}
                          </Stack>
                        }
                        color="teal"
                        disabled={disabled || blocked}
                        styles={{
                          body: { alignItems: 'flex-start' },
                          labelWrapper: { paddingTop: 1 },
                        }}
                      />
                    </UnstyledButton>
                  )
                })}
              </Stack>
            </Radio.Group>
            {g.textInput && (!g.textInput.whenChoice || g.textInput.whenChoice === current) ? (
              <TextInput
                label={g.textInput.label}
                description={g.textInput.hint}
                placeholder={g.textInput.placeholder}
                value={value[g.textInput.id] || ''}
                disabled={disabled}
                onChange={(ev) => onChange({ ...value, [g.textInput!.id]: ev.currentTarget.value })}
              />
            ) : null}
          </Stack>
        )
      })}
    </Stack>
  )
}
