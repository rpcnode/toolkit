import { OPSTACK_NETWORK_IDS } from './opstack'

/**
 * Networks with a parent-chain endpoint of their own (network.yml `l1Parent`): L2s that read batches from Ethereum, Xai
 * (an L3 on Arbitrum One) and Starknet (Juno verifies against Ethereum over a WebSocket).
 */
const L2_NETWORKS = new Set(['arb', 'base', 'robinhood', 'optimism', 'xai', 'starknet', ...OPSTACK_NETWORK_IDS])

export function isL2Network(network?: string | null): boolean {
  return L2_NETWORKS.has(String(network || '').toLowerCase())
}

/** Which chain a node's parent endpoint belongs to and what it needs; Ethereum RPC + beacon unless listed in [PARENT_OVERRIDES]. */
export type L1ParentSpec = {
  /** Network whose active nodes of this panel are offered as the parent. */
  chain: 'ethereum' | 'arb'
  /** false = no beacon / blob API (the form shows the RPC field only). */
  beacon: boolean
  /** true = the endpoint must be ws:// or wss:// (Juno --eth-node). */
  ws: boolean
  /** Short label of the right-pane row. */
  short: string
  /** Name of the parent chain in prose. */
  name: string
}

const DEFAULT_PARENT: L1ParentSpec = { chain: 'ethereum', beacon: true, ws: false, short: 'L1 · ETH', name: 'Ethereum' }

const PARENT_OVERRIDES: Record<string, L1ParentSpec> = {
  xai: { chain: 'arb', beacon: false, ws: false, short: 'L1 · ARB1', name: 'Arbitrum One' },
  starknet: { chain: 'ethereum', beacon: false, ws: true, short: 'L1 · ETH WS', name: 'Ethereum (WebSocket)' },
}

export function l1ParentSpec(network?: string | null): L1ParentSpec {
  return PARENT_OVERRIDES[String(network || '').toLowerCase()] ?? DEFAULT_PARENT
}
