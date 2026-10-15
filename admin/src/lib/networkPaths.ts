/**
 * Host path prefixes per network.
 * Mirror of internal/spec.Chain.PathPrefixes — do not invent a third list.
 * Data lives under /data/rpcnode/<root>; legacy /data/<root> kept for sanitize.
 */
export const PRODUCT_DATA_ROOT = 'rpcnode'

export const CHAIN_PATH_PREFIXES: Record<string, readonly string[]> = {
  bitcoin: ['/data/rpcnode/bitcoin', '/data/bitcoin', '/etc/bitcoin', '/opt/bitcoin'],
  solana: ['/data/rpcnode/solana', '/data/solana', '/etc/solana', '/opt/solana'],
  ethereum: ['/data/rpcnode/ethereum', '/data/ethereum', '/etc/ethereum', '/opt/ethereum'],
  polygon: ['/data/rpcnode/polygon', '/data/polygon', '/etc/polygon', '/opt/polygon'],
  bsc: ['/data/rpcnode/bsc', '/data/bsc', '/etc/bsc', '/opt/bsc'],
  hyperliquid: [
    '/data/rpcnode/hyperliquid',
    '/data/hyperliquid',
    '/etc/hyperliquid',
    '/opt/hyperliquid',
  ],
  arb: ['/data/rpcnode/arbitrum', '/data/arbitrum', '/etc/arbitrum', '/opt/arbitrum'],
  robinhood: ['/data/rpcnode/robinhood', '/data/robinhood', '/etc/robinhood', '/opt/robinhood'],
  xai: ['/data/rpcnode/xai', '/data/xai', '/etc/xai', '/opt/xai'],
  starknet: ['/data/rpcnode/starknet', '/data/starknet', '/etc/starknet', '/opt/starknet'],
  optimism: ['/data/rpcnode/optimism', '/data/optimism', '/etc/optimism', '/opt/optimism'],
  unichain: ['/data/rpcnode/unichain', '/data/unichain', '/etc/unichain', '/opt/unichain'],
  ink: ['/data/rpcnode/ink', '/data/ink', '/etc/ink', '/opt/ink'],
  soneium: ['/data/rpcnode/soneium', '/data/soneium', '/etc/soneium', '/opt/soneium'],
  worldchain: ['/data/rpcnode/worldchain', '/data/worldchain', '/etc/worldchain', '/opt/worldchain'],
  lisk: ['/data/rpcnode/lisk', '/data/lisk', '/etc/lisk', '/opt/lisk'],
  mode: ['/data/rpcnode/mode', '/data/mode', '/etc/mode', '/opt/mode'],
  zora: ['/data/rpcnode/zora', '/data/zora', '/etc/zora', '/opt/zora'],
  base: ['/data/rpcnode/base', '/data/base', '/etc/base', '/opt/base'],
  zcash: ['/data/rpcnode/zcash', '/data/zcash', '/etc/zcash', '/opt/zcash'],
  sui: ['/data/rpcnode/sui', '/data/sui', '/etc/sui', '/opt/sui'],
  aptos: ['/data/rpcnode/aptos', '/data/aptos', '/etc/aptos', '/opt/aptos'],
  avalanche: ['/data/rpcnode/avalanche', '/data/avalanche', '/etc/avalanche', '/opt/avalanche'],
  arc: ['/data/rpcnode/arc', '/data/arc', '/etc/arc', '/opt/arc'],
  xrpl: ['/data/rpcnode/xrpl', '/data/xrpl', '/etc/xrpl', '/opt/xrpl'],
  doge: ['/data/rpcnode/doge', '/data/doge', '/etc/doge', '/opt/doge'],
  ltc: ['/data/rpcnode/ltc', '/data/ltc', '/etc/ltc', '/opt/ltc'],
  dash: ['/data/rpcnode/dash', '/data/dash', '/etc/dash', '/opt/dash'],
  bch: ['/data/rpcnode/bch', '/data/bch', '/etc/bch', '/opt/bch'],
  cardano: ['/data/rpcnode/cardano', '/data/cardano', '/etc/cardano', '/opt/cardano'],
  stellar: ['/data/rpcnode/stellar', '/data/stellar', '/etc/stellar', '/opt/stellar'],
  cosmos: ['/data/rpcnode/cosmos', '/data/cosmos', '/etc/cosmos', '/opt/cosmos'],
  osmosis: ['/data/rpcnode/osmosis', '/data/osmosis', '/etc/osmosis', '/opt/osmosis'],
  injective: ['/data/rpcnode/injective', '/data/injective', '/etc/injective', '/opt/injective'],
  sei: ['/data/rpcnode/sei', '/data/sei', '/etc/sei', '/opt/sei'],
  celestia: ['/data/rpcnode/celestia', '/data/celestia', '/etc/celestia', '/opt/celestia'],
  xrplevm: ['/data/rpcnode/xrplevm', '/data/xrplevm', '/etc/xrplevm', '/opt/xrplevm'],
  ton: ['/data/rpcnode/ton', '/data/ton', '/etc/ton', '/opt/ton'],
  etc: ['/data/rpcnode/etc', '/data/etc', '/etc/etc', '/opt/etc'],
  tron: ['/data/rpcnode/tron', '/data/tron', '/etc/tron', '/opt/tron'],
  polkadot: ['/data/rpcnode/polkadot', '/data/polkadot', '/etc/polkadot', '/opt/polkadot'],
  near: ['/data/rpcnode/near', '/data/near', '/etc/near', '/opt/near'],
  fuel: ['/data/rpcnode/fuel', '/data/fuel', '/etc/fuel', '/opt/fuel'],
  stacks: ['/data/rpcnode/stacks', '/data/stacks', '/etc/stacks', '/opt/stacks'],
  aleo: ['/data/rpcnode/aleo', '/data/aleo', '/etc/aleo', '/opt/aleo'],
  gnosis: ['/data/rpcnode/gnosis', '/data/gnosis', '/etc/gnosis', '/opt/gnosis'],
  joc: ['/data/rpcnode/joc', '/data/joc', '/etc/joc', '/opt/joc'],
  flare: ['/data/rpcnode/flare', '/data/flare', '/etc/flare', '/opt/flare'],
  sonic: ['/data/rpcnode/sonic', '/data/sonic', '/etc/sonic', '/opt/sonic'],
  kaia: ['/data/rpcnode/kaia', '/data/kaia', '/etc/kaia', '/opt/kaia'],
  monad: ['/data/rpcnode/monad', '/data/monad', '/etc/monad', '/opt/monad'],
  vana: ['/data/rpcnode/vana', '/data/vana', '/etc/vana', '/opt/vana'],
  tempo: ['/data/rpcnode/tempo', '/data/tempo', '/etc/tempo', '/opt/tempo'],
  berachain: ['/data/rpcnode/berachain', '/data/berachain', '/etc/berachain', '/opt/berachain'],
  fluent: ['/data/rpcnode/fluent', '/data/fluent', '/etc/fluent', '/opt/fluent'],
  gravity: ['/data/rpcnode/gravity', '/data/gravity', '/etc/gravity', '/opt/gravity'],
}

/** Data dir leaf overrides — keep in sync with internal/spec. */
const DATA_LEAF: Record<string, Record<string, string>> = {
  ltc: { testnet: 'testnet4' },
  avalanche: { testnet: 'fuji' },
}

function chainPrefix(network: string, kind: 0 | 1 | 2): string {
  const n = (network || '').toLowerCase().trim()
  const prefixes = CHAIN_PATH_PREFIXES[n]
  // kind 0 = data (first entry = new rpcnode path), 1 = etc, 2 = opt
  if (kind === 0) {
    if (prefixes?.[0]) return prefixes[0]
    return n ? `/data/${PRODUCT_DATA_ROOT}/${n}` : `/data/${PRODUCT_DATA_ROOT}`
  }
  if (kind === 1) {
    const etc = prefixes?.find((p) => p.startsWith('/etc/'))
    if (etc) return etc
    return n ? `/etc/${n}` : '/etc'
  }
  const opt = prefixes?.find((p) => p.startsWith('/opt/'))
  if (opt) return opt
  return n ? `/opt/${n}` : '/opt'
}

function envLeaf(network: string, env: string, kind: 0 | 1 | 2): string {
  const e = (env || 'mainnet').toLowerCase().trim() || 'mainnet'
  if (kind === 0) {
    return DATA_LEAF[network]?.[e] ?? e
  }
  if (network === 'avalanche' && e === 'testnet') {
    return 'fuji'
  }
  return e
}

export function chainDataPath(network: string, env: string): string {
  return `${chainPrefix(network, 0)}/${envLeaf(network, env, 0)}`
}

export function chainEtcPath(network: string, env: string): string {
  return `${chainPrefix(network, 1)}/${envLeaf(network, env, 1)}`
}

export function chainOptPath(network: string, env: string): string {
  return `${chainPrefix(network, 2)}/${envLeaf(network, env, 2)}`
}

/** Host dir name under data/etc/opt (arb → arbitrum). Not including rpcnode/. */
export function chainRoot(network: string): string {
  const etc = chainPrefix(network, 1)
  return etc.replace(/^\/etc\//, '')
}

/**
 * <mount>/rpcnode/<root>/<env>[/<leaf>] — mirror of spec.JoinData.
 * Empty / / /data → /data/rpcnode/…
 */
export function pathOnDataMount(
  mount: string,
  network: string,
  env: string,
  leaf = '',
): string {
  const m = (mount || '').trim().replace(/\/$/, '')
  const root = chainRoot(network)
  const e = envLeaf(network, env, 0)
  const leafClean = (leaf || '').replace(/^\/+|\/+$/g, '')
  let base: string
  if (!m || m === '/' || m === '/data') {
    base = `/data/${PRODUCT_DATA_ROOT}/${root}/${e}`
  } else {
    base = `${m}/${PRODUCT_DATA_ROOT}/${root}/${e}`
  }
  return leafClean ? `${base}/${leafClean}` : base
}

/** True when text names another network's host paths than `network`. */
export function isForeignChainDiskError(
  text: string | null | undefined,
  network?: string | null,
): boolean {
  const want = (network || '').toLowerCase().trim()
  if (!want) return false
  const low = `${text || ''}`.toLowerCase()
  for (const [net, prefixes] of Object.entries(CHAIN_PATH_PREFIXES)) {
    if (net === want) continue
    if (prefixes.some((p) => low.includes(p))) return true
  }
  return false
}
