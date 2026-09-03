import type { LifecycleStep, StatusPayload } from '../types'
import { isSolanaDiskProjection } from './nodeLifecycle'
import { isSolanaNetwork, resolveNetwork } from './network'

/**
 * Setup Lane — one ordered rail for NODE SETUP.
 *
 * ports → install → [fetch/preflight] → [snapshot] → start → [verify/cleanup] → run
 *
 * Agent owns step status. UI paints the lane and, on error, retries ONLY that
 * step. Never wipe a downloaded snapshot on retry.
 */
export type SetupLaneId = 'ports' | 'install' | 'snapshot' | 'start' | 'run'

export type SetupLaneRetry = 'check_ports' | 'provision' | 'snapshot_start' | 'node_start'

export type WizardLaneId = 'ports' | 'install' | 'clients' | 'snapshot' | 'start' | 'sync'

export type ClassifySetupErrorCtx = {
  allowSnap?: boolean
  snapReady?: boolean
  /** Caller already knows which step was running. */
  hint?: SetupLaneId
}

const SNAP_FAIL =
  /snapshot|unpack error|failed to unpack|will not fit|failed to load bank|enospc|insufficient disk|aria2|wget|mithril|fetch-snapshot|init\.url/i
const START_FAIL =
  /start failed|node start|unit=|activating|crash-loop|failed to start|journal/i
const PORTS_FAIL =
  /port_busy|ports busy|check ports|timed out|agent_timeout|reach|filtered/i
const CLIENTS_FAIL =
  /client sync|client-config|no_client_config|template_missing|no_disk_layout|base-reth-node missing|sync base clients|sync clients/i
const INSTALL_FAIL = /provision|install|host_deps|unsupported_network|unsupported_env/i

/** Map a failure message to the lane step that owns it. */
export function classifySetupError(msg: string, ctx?: ClassifySetupErrorCtx): SetupLaneId {
  const text = String(msg || '').trim()
  if (isSolanaDiskProjection(text)) return ctx?.hint || 'start'
  if (CLIENTS_FAIL.test(text)) return 'install'
  if (SNAP_FAIL.test(text)) return 'snapshot'
  if (PORTS_FAIL.test(text)) return 'ports'
  if (START_FAIL.test(text)) return 'start'
  if (INSTALL_FAIL.test(text)) return 'install'
  if (ctx?.hint) return ctx.hint
  if (ctx?.allowSnap && !ctx.snapReady) return 'snapshot'
  if (ctx?.allowSnap && ctx.snapReady) return 'start'
  return 'install'
}

function stepLooksError(s?: LifecycleStep | null): boolean {
  if (!s) return false
  const st = (s.status || '').toLowerCase()
  return st === 'error' || s.error === true
}

/** First lane step the agent (or wizard) marked as failed. */
export function setupLaneFailedId(
  status?: StatusPayload | null,
  allowSnap?: boolean,
  extras?: {
    portsError?: string | null
    wizardError?: string | null
    wizardHint?: SetupLaneId | null
  },
): SetupLaneId | null {
  const raw = String(status?.lifecycle?.failed_step || '').toLowerCase()
  if (raw === 'ports' || raw === 'install' || raw === 'start' || raw === 'run') {
    return raw === 'run' ? 'start' : raw
  }
  if (raw === 'fetch' || raw === 'preflight') return 'install'
  if (raw === 'verify' || raw === 'cleanup') return 'start'
  if (raw === 'snapshot' && allowSnap !== false) return 'snapshot'

  const steps = status?.lifecycle?.steps || []
  for (const s of steps) {
    if (!stepLooksError(s)) continue
    const id = (s.id || '').toLowerCase()
    if (id === 'ports' || id === 'install' || id === 'start') return id
    if (id === 'fetch' || id === 'preflight') return 'install'
    if (id === 'verify' || id === 'cleanup') return 'start'
    if (id === 'snapshot' && allowSnap !== false) return 'snapshot'
    if (id === 'run') return 'start'
  }

  const ns = (status?.lifecycle?.node_status || status?.node_status || '').toLowerCase()
  const phase = (status?.lifecycle?.phase || '').toLowerCase()
  if (allowSnap !== false) {
    if (
      status?.snapshot?.failed ||
      ns === 'snapshot_error' ||
      (phase === 'error' && SNAP_FAIL.test(String(status?.lifecycle?.detail || '')))
    ) {
      return 'snapshot'
    }
  }
  if (ns === 'start_error') return 'start'

  if (extras?.portsError) return 'ports'
  if (extras?.wizardHint) return extras.wizardHint
  if (extras?.wizardError) {
    return classifySetupError(extras.wizardError, { allowSnap, hint: extras.wizardHint || undefined })
  }
  return null
}

/** Wizard left-rail id for a failed lane step (run lives under Sync). */
export function wizardStepFromFailedLane(id: SetupLaneId): WizardLaneId {
  if (id === 'run') return 'sync'
  return id
}

/** Which host action retries this step — never a full reinstall. */
export function retryActionForLane(id: SetupLaneId): SetupLaneRetry | null {
  switch (id) {
    case 'ports':
      return 'check_ports'
    case 'install':
      return 'provision'
    case 'snapshot':
      return 'snapshot_start'
    case 'start':
      return 'node_start'
    default:
      return null
  }
}

export function setupLaneRetryLabel(id: SetupLaneId): string {
  switch (id) {
    case 'ports':
      return 'Retry ports'
    case 'install':
      return 'Retry install'
    case 'snapshot':
      return 'Retry snapshot'
    case 'start':
      return 'Retry start'
    default:
      return 'Retry'
  }
}

/** ExtraStep is the chain unit (Solana Agave / Robinhood nitro --init.url). */
export function snapshotStartsViaNode(
  status?: StatusPayload | null,
  networkHint?: string | null,
): boolean {
  if (isSolanaNetwork(resolveNetwork(status, networkHint))) return true
  const p = status?.lifecycle?.profile
  if (!p) return false
  if (p.snapshot_via_node === true) return true
  return String(p.snapshot_bootstrap || '').toLowerCase() === 'via_node'
}

/** Keep NODE SETUP + Retry when a lane step failed (even if the unit is stopped). */
export function setupLaneNeedsWizard(
  status?: StatusPayload | null,
  workload?: { status?: string } | null,
): boolean {
  const ns = (status?.lifecycle?.node_status || status?.node_status || '').toLowerCase()
  const wl = (workload?.status || '').toLowerCase()
  if (ns === 'snapshot_error' || ns === 'start_error' || ns === 'needs_snapshot') return true
  if (wl === 'snapshot_error' || wl === 'start_error') return true
  if (status?.snapshot?.failed) return true
  if (String(status?.lifecycle?.failed_step || '').trim()) return true
  if (String(status?.start_error || '').trim()) return true
  return false
}
