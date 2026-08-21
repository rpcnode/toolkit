import type { AgentCapabilities, StatusPayload } from '../types'

/** True for bitcoin / btc chain ids. */
export function isBitcoinNetwork(network?: string | null): boolean {
  const n = (network || '').toLowerCase().trim()
  return n === 'bitcoin' || n === 'btc'
}

/** True for solana chain ids. */
export function isSolanaNetwork(network?: string | null): boolean {
  return (network || '').toLowerCase().trim() === 'solana'
}

/** True for Stellar / Soroban RPC. */
export function isStellarNetwork(network?: string | null): boolean {
  return (network || '').toLowerCase().trim() === 'stellar'
}

/** True for Toncoin / TON liteserver. */
export function isTonNetwork(network?: string | null): boolean {
  return (network || '').toLowerCase().trim() === 'ton'
}

/** True for TRON / java-tron. */
export function isTronNetwork(network?: string | null): boolean {
  return (network || '').toLowerCase().trim() === 'tron'
}

/** True for ethereum / eth chain ids. */
export function isEthereumNetwork(network?: string | null): boolean {
  const n = (network || '').toLowerCase().trim()
  return n === 'ethereum' || n === 'eth'
}

/** True for BNB Smart Chain. */
export function isBscNetwork(network?: string | null): boolean {
  return (network || '').toLowerCase().trim() === 'bsc'
}

/** True for XRP Ledger. */
export function isXrplNetwork(network?: string | null): boolean {
  const n = (network || '').toLowerCase().trim()
  return n === 'xrpl' || n === 'xrp'
}

/** Networks that never use TRON snapshot bootstrap. */
export function isNoSnapshotNetwork(network?: string | null): boolean {
  const n = (network || '').toLowerCase().trim()
  return (
    isBitcoinNetwork(network) ||
    isSolanaNetwork(network) ||
    isEthereumNetwork(network) ||
    n === 'hyperliquid' ||
    n === 'arb' ||
    n === 'robinhood' ||
    n === 'optimism' ||
    n === 'base' ||
    n === 'doge' ||
    n === 'ltc' ||
    n === 'dash' ||
    n === 'bch' ||
    n === 'xrpl' ||
    n === 'stellar' ||
    n === 'ton' ||
    n === 'etc' ||
    n === 'zcash' ||
    n === 'aptos' ||
    n === 'avalanche'
  )
}

/** Regtest (bitcoin / doge / ltc / dash / bch) — local chain; never treat as public IBD sync. */
export function isBitcoinRegtestEnv(env?: string | null): boolean {
  return (env || '').toLowerCase().trim() === 'regtest'
}

export function resolveEnv(status?: StatusPayload | null): string {
  return (
    status?.view_env ||
    status?.env ||
    status?.agent_env ||
    (status?.instance as { env?: string } | undefined)?.env ||
    ''
  ).toLowerCase()
}

/** Resolve chain network from workload hint + status payload. */
export function resolveNetwork(
  status?: StatusPayload | null,
  networkHint?: string | null,
): string {
  return (
    networkHint ||
    status?.lifecycle?.profile?.network ||
    (status?.instance as { network?: string } | undefined)?.network ||
    status?.panel_network ||
    ''
  ).toLowerCase()
}

/** Bitcoin never uses TRON snapshot bootstrap — IBD/sync only. */
export function isBitcoinStatus(
  status?: StatusPayload | null,
  networkHint?: string | null,
): boolean {
  return isBitcoinNetwork(resolveNetwork(status, networkHint))
}

function asStringList(v: unknown): string[] | null {
  if (!Array.isArray(v) || v.length === 0) return null
  return v.map((s) => String(s).toLowerCase().trim()).filter(Boolean)
}

/**
 * Explicit `supported_steps` from agent healthz/status/lifecycle.
 * Does NOT use profile.step_ids (runtime cursor ≠ static catalog).
 * Returns null when the field is absent (legacy agents).
 */
export function resolveSupportedSteps(
  status?: StatusPayload | null,
): string[] | null {
  if (!status) return null
  const caps = status.capabilities || status.lifecycle?.capabilities
  const candidates: unknown[] = [
    status.supported_steps,
    status.lifecycle?.supported_steps,
    status.lifecycle?.profile?.supported_steps,
    caps && typeof caps === 'object'
      ? (caps as AgentCapabilities).supported_steps
      : null,
  ]
  for (const c of candidates) {
    const list = asStringList(c)
    if (list) return list
  }
  return null
}

export function resolveCapabilities(
  status?: StatusPayload | null,
): AgentCapabilities | null {
  const caps =
    status?.capabilities ||
    status?.lifecycle?.capabilities ||
    status?.lifecycle?.profile?.capabilities
  if (caps && typeof caps === 'object') return caps as AgentCapabilities
  return null
}

/**
 * Whether the network profile supports a lifecycle step id (e.g. "snapshot").
 *
 * Priority:
 * 1. `supported_steps` present → ONLY that list
 * 2. else `capabilities.snapshot` / `.ibd` boolean
 * 3. else temporary bitcoin fallback; TRON assumes snapshot
 */
export function supportsLifecycleStep(
  status: StatusPayload | null | undefined,
  stepId: string,
  networkHint?: string | null,
): boolean {
  const id = stepId.toLowerCase()
  const steps = resolveSupportedSteps(status)
  const caps = resolveCapabilities(status)
  const net = resolveNetwork(status, networkHint)

  // Catalog snapshot networks (TRON / Sui): workload.network wins over tip
  // status.json. Host tip always has snapshot.enabled=false and empty steps —
  // that used to drop Snapshot and call /nodes/start (genesis IBD + wget).
  if (id === 'snapshot') {
    if (isNoSnapshotNetwork(net)) return false
    if (net) return true
  }

  if (steps) {
    if (steps.includes(id)) return true
    // Agents catalog full-sync as "run", not "ibd". Feature flag is capabilities.ibd.
    if (id === 'ibd') {
      if (caps && typeof caps.ibd === 'boolean') return caps.ibd
      if (steps.includes('run') && isNoSnapshotNetwork(net)) return true
      return false
    }
    return false
  }

  if (id === 'snapshot' && caps && typeof caps.snapshot === 'boolean') {
    return caps.snapshot
  }
  if (id === 'ibd' && caps && typeof caps.ibd === 'boolean') {
    return caps.ibd
  }

  // Temporary until agents without supported_steps are gone.
  if (id === 'snapshot') {
    if (status?.host_tip || status?.lifecycle?.phase === 'host') return false
    if (status?.snapshot?.enabled === false) return false
    return true
  }
  if (id === 'ibd') {
    return (
      isBitcoinStatus(status, networkHint) ||
      isEthereumNetwork(net) ||
      isBscNetwork(net) ||
      isNoSnapshotNetwork(net)
    )
  }
  return true
}

/** Profile supports TRON-style snapshot bootstrap (agent-declared). */
export function supportsSnapshotStep(
  status?: StatusPayload | null,
  networkHint?: string | null,
): boolean {
  return supportsLifecycleStep(status, 'snapshot', networkHint)
}

/** Profile uses IBD instead of snapshot (bitcoin). Regtest never shows IBD mode. */
export function supportsIbdStep(
  status?: StatusPayload | null,
  networkHint?: string | null,
): boolean {
  if (isBitcoinRegtestEnv(resolveEnv(status))) return false
  return supportsLifecycleStep(status, 'ibd', networkHint)
}
