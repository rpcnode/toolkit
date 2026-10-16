/**
 * OP Stack family networks that run op-reth (or the World Chain reth fork) + op-node from a Superchain Registry name
 * (`chains/opstack` in the backend). `optimism` keeps its own package and is NOT part of this set — add it where a screen
 * should treat both alike (see `isOpL2Network`).
 */
export const OPSTACK_NETWORK_IDS = ['unichain', 'ink', 'soneium', 'worldchain', 'lisk', 'mode', 'zora'] as const

const OPSTACK = new Set<string>(OPSTACK_NETWORK_IDS)

export function isOpstackNetwork(network?: string | null): boolean {
  return OPSTACK.has(String(network || '').toLowerCase().trim())
}

/** optimism + the opstack family: same run script shape, same disk roles, same Ethereum parent chain. */
export function isOpL2Network(network?: string | null): boolean {
  const n = String(network || '').toLowerCase().trim()
  return n === 'optimism' || OPSTACK.has(n)
}

/**
 * Networks with no public datadir snapshot: without a `snapshot_url` the node derives the chain from L1 (weeks).
 * The wizard asks for the URL for these (optional elsewhere).
 */
export function opstackNeedsSnapshotUrl(network?: string | null): boolean {
  const n = String(network || '').toLowerCase().trim()
  return n === 'mode' || n === 'zora'
}
