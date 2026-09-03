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

/** True for Polygon PoS (bor + heimdall). */
export function isPolygonNetwork(network?: string | null): boolean {
  const n = (network || '').toLowerCase().trim()
  return n === 'polygon' || n === 'matic'
}

/** True for BNB Smart Chain. */
export function isBscNetwork(network?: string | null): boolean {
  return (network || '').toLowerCase().trim() === 'bsc'
}

/** True for Base (OP Stack L2). */
export function isBaseNetwork(network?: string | null): boolean {
  return (network || '').toLowerCase().trim() === 'base'
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
    isEthereumNetwork(network) ||
    isPolygonNetwork(network) ||
    n === 'hyperliquid' ||
    n === 'arb' ||
    n === 'robinhood' ||
    n === 'optimism' ||
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
 * Panel catalog flag from `GET /api/nodes/{id}` → `needs_snapshot`.
 * Wins over agent `supported_steps` when the Kotlin network facts say snapshot is required.
 */
export function workloadNeedsSnapshot(
  workload?: { needs_snapshot?: boolean } | null,
  status?: StatusPayload | null,
  networkHint?: string | null,
  envHint?: string | null,
): boolean {
  const env = (envHint || resolveEnv(status)).toLowerCase().trim()
  if (env === 'localnet') return false
  if (workload?.needs_snapshot === true) return true
  return supportsSnapshotStep(status, networkHint, envHint)
}

/**
 * Whether the network profile supports a lifecycle step id (e.g. "snapshot").
 *
 * Priority:
 * 0. `panelNeedsSnapshot` from panel node row — catalog `snapshot: required`
 * 1. `supported_steps` present → ONLY that list (env-aware: tron/shasta omits snapshot)
 * 2. else `capabilities.snapshot` / `.ibd` boolean
 * 3. else network/env heuristics (TRON mainnet/nile assume snapshot; shasta does not)
 */
export function supportsLifecycleStep(
  status: StatusPayload | null | undefined,
  stepId: string,
  networkHint?: string | null,
  envHint?: string | null,
  panelNeedsSnapshot?: boolean,
): boolean {
  const id = stepId.toLowerCase()
  if (id === 'snapshot' && panelNeedsSnapshot) return true
  const steps = resolveSupportedSteps(status)
  const caps = resolveCapabilities(status)
  const net = resolveNetwork(status, networkHint)
  const env = (envHint || resolveEnv(status)).toLowerCase().trim()

  // Agent catalog wins when present. Do NOT short-circuit TRON→snapshot before
  // this: that painted Snapshot forever on tron/shasta and POSTed oneshot start
  // against a unit that does not exist.
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

  if (id === 'snapshot') {
    if (isSolanaNetwork(net)) {
      return env !== 'localnet'
    }
    // TRON Shasta: no official tarball — IBD only (same as catalog SnapNever).
    if (isTronNetwork(net) && env === 'shasta') return false
    if (isNoSnapshotNetwork(net)) return false
    // Without supported_steps: tip status.json used to drop TRON Snapshot —
    // workload.network still implies ExtraStep for mainnet/nile/sui/…
    if (net) return true
    if (caps && typeof caps.snapshot === 'boolean') return caps.snapshot
    if (status?.host_tip || status?.lifecycle?.phase === 'host') return false
    if (status?.snapshot?.enabled === false) return false
    return true
  }

  if (id === 'ibd') {
    if (caps && typeof caps.ibd === 'boolean') return caps.ibd
    return (
      isBitcoinStatus(status, networkHint) ||
      isEthereumNetwork(net) ||
      isBscNetwork(net) ||
      isNoSnapshotNetwork(net)
    )
  }
  return true
}

/** Profile supports TRON-style snapshot bootstrap (agent-declared or panel catalog). */
export function supportsSnapshotStep(
  status?: StatusPayload | null,
  networkHint?: string | null,
  envHint?: string | null,
  panelNeedsSnapshot?: boolean,
): boolean {
  return supportsLifecycleStep(status, 'snapshot', networkHint, envHint, panelNeedsSnapshot)
}

/** Profile uses IBD instead of snapshot (bitcoin). Regtest never shows IBD mode. */
export function supportsIbdStep(
  status?: StatusPayload | null,
  networkHint?: string | null,
): boolean {
  if (isBitcoinRegtestEnv(resolveEnv(status))) return false
  return supportsLifecycleStep(status, 'ibd', networkHint)
}
