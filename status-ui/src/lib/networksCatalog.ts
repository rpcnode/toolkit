/** Canonical panel network catalog (Add node + Nodes filter) — A–Z by label. */
export const NETWORK_OPTIONS = [
  { value: 'aptos', label: 'Aptos' },
  { value: 'arb', label: 'Arbitrum' },
  { value: 'avalanche', label: 'Avalanche' },
  { value: 'base', label: 'Base' },
  { value: 'bitcoin', label: 'Bitcoin' },
  { value: 'bch', label: 'Bitcoin Cash' },
  { value: 'bsc', label: 'BNB Smart Chain' },
  { value: 'cardano', label: 'Cardano' },
  { value: 'dash', label: 'Dash' },
  { value: 'doge', label: 'Dogecoin' },
  { value: 'etc', label: 'Ethereum Classic' },
  { value: 'ethereum', label: 'Ethereum' },
  { value: 'hyperliquid', label: 'Hyperliquid' },
  { value: 'ltc', label: 'Litecoin' },
  { value: 'optimism', label: 'Optimism' },
  { value: 'robinhood', label: 'Robinhood' },
  { value: 'solana', label: 'Solana' },
  { value: 'stellar', label: 'Stellar' },
  { value: 'sui', label: 'Sui' },
  { value: 'ton', label: 'Toncoin' },
  { value: 'tron', label: 'TRON' },
  { value: 'xrpl', label: 'XRP Ledger' },
  { value: 'zcash', label: 'Zcash' },
] as const

export type NetworkId = (typeof NETWORK_OPTIONS)[number]['value']

export const ENVS_BY_NETWORK: Record<string, { value: string; label: string }[]> = {
  tron: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'nile', label: 'nile' },
    { value: 'shasta', label: 'shasta' },
  ],
  bitcoin: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'testnet4', label: 'testnet4' },
    { value: 'signet', label: 'signet' },
    { value: 'regtest', label: 'regtest' },
  ],
  solana: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'testnet', label: 'testnet' },
    { value: 'devnet', label: 'devnet' },
    { value: 'localnet', label: 'localnet' },
  ],
  ethereum: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'sepolia', label: 'sepolia' },
    { value: 'hoodi', label: 'hoodi' },
  ],
  etc: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'mordor', label: 'mordor' },
  ],
  bsc: [
    { value: 'testnet', label: 'testnet' },
    { value: 'mainnet', label: 'mainnet' },
  ],
  hyperliquid: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'testnet', label: 'testnet' },
  ],
  arb: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'sepolia', label: 'sepolia' },
  ],
  optimism: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'sepolia', label: 'sepolia' },
  ],
  base: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'sepolia', label: 'sepolia' },
  ],
  robinhood: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'testnet', label: 'testnet' },
  ],
  xrpl: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'testnet', label: 'testnet' },
  ],
  doge: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'testnet', label: 'testnet' },
  ],
  ltc: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'testnet', label: 'testnet' },
    { value: 'regtest', label: 'regtest' },
  ],
  dash: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'testnet', label: 'testnet' },
    { value: 'regtest', label: 'regtest' },
  ],
  bch: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'testnet', label: 'testnet' },
    { value: 'regtest', label: 'regtest' },
  ],
  cardano: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'preprod', label: 'preprod' },
    { value: 'preview', label: 'preview' },
  ],
  stellar: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'testnet', label: 'testnet' },
    { value: 'futurenet', label: 'futurenet' },
  ],
  ton: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'testnet', label: 'testnet' },
  ],
  sui: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'testnet', label: 'testnet' },
  ],
  aptos: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'testnet', label: 'testnet' },
  ],
  avalanche: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'fuji', label: 'fuji' },
  ],
  zcash: [
    { value: 'mainnet', label: 'mainnet' },
    { value: 'testnet', label: 'testnet' },
  ],
}

export function networkLabel(id: string): string {
  const hit = NETWORK_OPTIONS.find((n) => n.value === id.toLowerCase())
  if (hit) return hit.label
  return id
}

/** Networks that allow only one env per host (agent network_constraints.one_env_per_host). */
export function networkOneEnvPerHost(network: string): boolean {
  const n = network.toLowerCase()
  return n === 'hyperliquid' || n === 'ton'
}
