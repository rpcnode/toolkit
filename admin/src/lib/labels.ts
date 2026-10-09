import type { StatusPayload } from '../types'
import { supportsSnapshotStep } from './network'
import { statusHonestlySynced } from './nodeLifecycle'

/** Human labels for header chips — never raw duplicate "setup". */
export function healthLabel(health?: string | null): string {
  switch ((health || '').toLowerCase()) {
    case 'ok':
    case 'healthy':
      return 'Healthy'
    case 'setup':
      return 'Bootstrapping'
    case 'degraded':
      return 'Degraded'
    case 'agent_unreachable':
      return 'Agent unreachable'
    case 'mismatch':
      return 'Wrong agent'
    case 'maintenance':
      return 'Maintenance'
    default:
      return health ? capitalize(health) : 'Unknown'
  }
}

export function snapshotPhaseLabel(phase?: string | null, opts?: { ready?: boolean; wget?: boolean; failed?: boolean }): string {
  if (opts?.failed) return 'Snapshot error'
  if (opts?.wget || (phase || '').toLowerCase() === 'download') return 'Downloading'
  if (opts?.ready || (phase || '').toLowerCase() === 'done') return 'Snapshot ready'
  switch ((phase || '').toLowerCase()) {
    case 'idle':
      return 'Snapshot idle'
    case 'extract':
    case 'extracting':
      return 'Extracting'
    case 'failed':
    case 'error':
      return 'Snapshot failed'
    case 'setup':
      return 'Needs snapshot'
    default:
      return phase ? `Snapshot: ${phase}` : 'Snapshot idle'
  }
}

export function nodeStatusLabel(status: StatusPayload): string {
  const rpc = status.rpc || {}
  const checks = status.checks || {}
  if (status.maintenance?.enabled || status.pause?.active) return 'RPC paused'
  if (rpc.reachable || rpc.http_ok) return 'Node online'
  const processUp =
    !!rpc.process_up ||
    !!checks.java_tron_process ||
    !!checks.node_process_up ||
    status.services?.node === 'active' ||
    status.services?.node === 'activating'
  if (processUp || !!rpc.port_open || !!checks.node_port_open) return 'Node starting'
  return 'Node offline'
}

export type HeaderChip = { key: string; label: string; color: string }

/** Distinct header chips — dedupes identical labels. */
export function buildHeaderChips(status: StatusPayload): HeaderChip[] {
  const chips: HeaderChip[] = []
  const seen = new Set<string>()

  const push = (key: string, label: string, color: string) => {
    const norm = label.trim().toLowerCase()
    if (!norm || seen.has(norm)) return
    seen.add(norm)
    chips.push({ key, label, color })
  }

  const env = status.view_env || status.env
  if (env) push('env', env, 'cyan')

  const health = (status.health || '').toLowerCase()
  push('health', healthLabel(status.health), healthChipColor(health))

  push('node', nodeStatusLabel(status), nodeChipColor(status))

  if (status.needs_provision) {
    push('setup', 'Setup', 'yellow')
    return chips
  }
  if (status.network_mismatch || (status.health || '').toLowerCase() === 'mismatch') {
    push('mismatch', 'Wrong agent', 'red')
    return chips
  }

  const snap = status.snapshot
  if (
    supportsSnapshotStep(status) &&
    snap?.enabled !== false &&
    !statusHonestlySynced(status)
  ) {
    const phase = snapshotPhaseLabel(snap?.phase, {
      ready: !!snap?.ready,
      wget: !!snap?.wget_running,
      failed: !!snap?.failed,
    })
    const interesting =
      !!snap?.wget_running ||
      !!snap?.failed ||
      (!snap?.ready && (status.ui_phase === 'setup' || !status.rpc?.reachable))
    if (interesting) {
      push('snapshot', phase, snapChipColor(snap))
    }
  }

  if (status.system_agent_stale) push('stale', 'System stale', 'orange')

  return chips
}

function healthChipColor(h: string): string {
  switch (h) {
    case 'ok':
    case 'healthy':
      return 'teal'
    case 'setup':
    case 'degraded':
    case 'maintenance':
      return 'yellow'
    case 'mismatch':
      return 'red'
    default:
      return 'red'
  }
}

function nodeChipColor(status: StatusPayload): string {
  if (status.maintenance?.enabled || status.pause?.active) return 'yellow'
  if (status.rpc?.reachable || status.rpc?.http_ok) return 'teal'
  const label = nodeStatusLabel(status)
  if (label === 'Node starting') return 'yellow'
  return 'red'
}

function snapChipColor(snap?: StatusPayload['snapshot']): string {
  if (snap?.failed) return 'red'
  if (snap?.wget_running) return 'yellow'
  if (snap?.ready) return 'teal'
  return 'gray'
}

function capitalize(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1)
}
