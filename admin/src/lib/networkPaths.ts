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
  optimism: ['/data/rpcnode/optimism', '/data/optimism', '/etc/optimism', '/opt/optimism'],
  base: ['/data/rpcnode/base', '/data/base', '/etc/base', '/opt/base'],
  zcash: ['/data/rpcnode/zcash', '/data/zcash', '/etc/zcash', '/opt/zcash'],
  sui: ['/data/rpcnode/sui', '/data/sui', '/etc/sui', '/opt/sui'],
  aptos: ['/data/rpcnode/aptos', '/data/aptos', '/etc/aptos', '/opt/aptos'],
  avalanche: ['/data/rpcnode/avalanche', '/data/avalanche', '/etc/avalanche', '/opt/avalanche'],
  xrpl: ['/data/rpcnode/xrpl', '/data/xrpl', '/etc/xrpl', '/opt/xrpl'],
  doge: ['/data/rpcnode/doge', '/data/doge', '/etc/doge', '/opt/doge'],
  ltc: ['/data/rpcnode/ltc', '/data/ltc', '/etc/ltc', '/opt/ltc'],
  dash: ['/data/rpcnode/dash', '/data/dash', '/etc/dash', '/opt/dash'],
  bch: ['/data/rpcnode/bch', '/data/bch', '/etc/bch', '/opt/bch'],
  cardano: ['/data/rpcnode/cardano', '/data/cardano', '/etc/cardano', '/opt/cardano'],
  stellar: ['/data/rpcnode/stellar', '/data/stellar', '/etc/stellar', '/opt/stellar'],
  ton: ['/data/rpcnode/ton', '/data/ton', '/etc/ton', '/opt/ton'],
  etc: ['/data/rpcnode/etc', '/data/etc', '/etc/etc', '/opt/etc'],
  tron: ['/data/rpcnode/tron', '/data/tron', '/etc/tron', '/opt/tron'],
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
