import { api, type AgentTarget, type Workload } from '../api'
import type { StatusPayload } from '../types'
import { agentLifecycleStepAcked } from './nodeLifecycle'

function sleep(ms: number) {
  return new Promise<void>((r) => setTimeout(r, ms))
}

type WizardStepId = 'ports' | 'install' | 'snapshot' | 'start' | 'sync' | 'done'

function mapPanelStatusToWizardStep(
  wlStatus: string | null | undefined,
  allowSnapshot: boolean,
): WizardStepId | null {
  const wl = (wlStatus || '').toLowerCase()
  if (!wl) return null
  if (
    wl === 'awaiting_ports' ||
    wl === 'ports_pending' ||
    wl === 'ports_confirmed' ||
    wl === 'ready_to_install'
  ) {
    return 'ports'
  }
  if (
    allowSnapshot &&
    (wl === 'needs_snapshot' ||
      wl === 'snapshot_running' ||
      wl === 'snapshot_error' ||
      wl === 'snapshot_complete')
  ) {
    return wl === 'snapshot_complete' ? 'start' : 'snapshot'
  }
  if (
    wl === 'starting' ||
    wl === 'start_error' ||
    wl === 'ready_to_start' ||
    wl === 'installing'
  ) {
    return 'start'
  }
  if (wl === 'sync' || wl === 'syncing' || wl === 'stopped') {
    return 'sync'
  }
  if (wl === 'active' || wl === 'running' || wl === 'working' || wl === 'online') {
    return 'done'
  }
  return null
}

function wizardStepToLifecycleCurrent(step: WizardStepId | null): string {
  switch (step) {
    case 'ports':
      return 'ports'
    case 'install':
      return 'install'
    case 'snapshot':
      return 'snapshot'
    case 'start':
      return 'start'
    case 'sync':
      // Prefer `run` — `ibd` + missing snapshot.ready used to force Snapshot in the wizard.
      return 'run'
    case 'done':
      return 'healthy'
    default:
      return ''
  }
}

/** Minimal lifecycle view derived from panel SQLite — no agent status.json. */
export function workloadToStatusPayload(
  wl: Workload | null | undefined,
  allowSnapshot = true,
): StatusPayload {
  if (!wl) return {} as StatusPayload
  const st = (wl.status || '').toLowerCase()
  const wizardStep = mapPanelStatusToWizardStep(st, allowSnapshot)
  const current = wizardStepToLifecycleCurrent(wizardStep)
  const height = wl.height != null && Number.isFinite(Number(wl.height)) ? Number(wl.height) : null
  const tip =
    wl.network_height != null && Number.isFinite(Number(wl.network_height))
      ? Number(wl.network_height)
      : null
  const sizeOnDisk =
    wl.size_on_disk != null && Number.isFinite(Number(wl.size_on_disk)) && Number(wl.size_on_disk) >= 0
      ? Number(wl.size_on_disk)
      : null
  const syncPct =
    wl.sync_pct != null && Number.isFinite(Number(wl.sync_pct)) && Number(wl.sync_pct) >= 0
      ? Number(wl.sync_pct)
      : null
  const syncing = st === 'sync' || (syncPct != null && syncPct < 99.9)
  // connect.ready only from panel status — never height/tip (0/0 broke Add node → wizard).
  const atTip = st === 'active' || st === 'online'
  return {
    node_status: st,
    view_env: wl.env,
    env: wl.env,
    lifecycle: {
      current,
      current_step_id: current,
      complete: wizardStep === 'done',
      phase: st,
      node_status: st,
      height: height ?? undefined,
    },
    rpc:
      height != null
        ? {
            node_height: height,
            network_height: tip ?? undefined,
          }
        : undefined,
    sync:
      height != null || sizeOnDisk != null || syncPct != null
        ? {
            blocks: height ?? undefined,
            headers: tip ?? height ?? undefined,
            syncing,
            ibd: syncing,
            verification_pct:
              syncPct != null ? syncPct : atTip ? 100 : undefined,
            size_on_disk: sizeOnDisk ?? undefined,
          }
        : undefined,
    connect: atTip ? { ready: true } : undefined,
  } as StatusPayload
}

function panelStepAcked(
  wl: Workload | null | undefined,
  stepId: string,
  acceptCurrent?: string[],
): boolean {
  if (!wl) return false
  const st = (wl.status || '').toLowerCase()
  const agentPort = Number(wl.agent_port || 0)
  const want = stepId.toLowerCase()

  if (acceptCurrent?.some((c) => c.toLowerCase() === st)) return true

  if (want === 'ports') {
    return agentPort > 0 && st !== 'awaiting_ports' && st !== 'ready_to_install'
  }
  if (want === 'install') {
    return (
      agentPort > 0 &&
      !['awaiting_ports', 'ready_to_install', 'ports_confirmed', 'ports_pending'].includes(st)
    )
  }
  if (want === 'snapshot') {
    return [
      'snapshot_running',
      'snapshot_complete',
      'starting',
      'ready_to_start',
      'sync',
      'active',
      'stopped',
    ].includes(st)
  }
  if (want === 'start') {
    return ['starting', 'sync', 'active', 'stopped'].includes(st)
  }
  return false
}

async function fetchPanelNode(nodeId: string): Promise<Workload | null> {
  try {
    const res = await api.workloadsGet(nodeId)
    return res.item ?? null
  } catch {
    return null
  }
}

/** Poll panel node row until lifecycle step ACK (replaces agent status.json). */
export async function waitPanelLifecycleAck(
  target: AgentTarget,
  stepId: string,
  opts?: {
    timeoutMs?: number
    dialGraceMs?: number
    onTick?: (st: StatusPayload) => void
    onWaiting?: (detail: string, waitedMs: number) => void
    notListening?: (detail: string, waitedMs: number) => string
    acceptCurrent?: string[]
    allowSnapshot?: boolean
  },
): Promise<StatusPayload> {
  const nodeId = target.node
  if (!nodeId) {
    throw new Error('Node id required for panel lifecycle ACK')
  }
  const timeoutMs = opts?.timeoutMs ?? 60_000
  const dialGraceMs = opts?.dialGraceMs ?? 0
  const started = Date.now()
  let deadline = started + timeoutMs
  let attempt = 0
  let lastWl: Workload | null = null
  let lastSynthetic: StatusPayload | null = null
  let lastWaitLog = 0

  while (Date.now() < deadline) {
    attempt++
    lastWl = await fetchPanelNode(nodeId)
    lastSynthetic = workloadToStatusPayload(lastWl, opts?.allowSnapshot ?? true)
    opts?.onTick?.(lastSynthetic)

    if (panelStepAcked(lastWl, stepId, opts?.acceptCurrent)) {
      return lastSynthetic
    }
    if (agentLifecycleStepAcked(lastSynthetic, stepId)) {
      return lastSynthetic
    }

    const st = (lastWl?.status || '').toLowerCase()
    const agentPort = Number(lastWl?.agent_port || 0)
    const waitingForAgent =
      agentPort <= 0 || st === 'awaiting_ports' || st === 'ready_to_install'
    if (waitingForAgent && dialGraceMs > 0) {
      deadline = Math.max(deadline, started + dialGraceMs)
      const now = Date.now()
      if (now - lastWaitLog > 10_000) {
        lastWaitLog = now
        opts?.onWaiting?.('Waiting for node agent ports on panel row', now - started)
      }
      await sleep(2000)
      continue
    }

    await sleep(attempt < 4 ? 800 : 1500)
  }

  const waited = Date.now() - started
  const st = (lastWl?.status || '').toLowerCase()
  if (Number(lastWl?.agent_port || 0) <= 0 && dialGraceMs > 0) {
    throw new Error(
      opts?.notListening?.('Node agent port not assigned yet', waited) ||
        `Node agent never registered on panel within ${Math.round(waited / 1000)}s`,
    )
  }
  throw new Error(
    st
      ? `Panel did not ACK ${stepId} (status=${st})`
      : `Panel did not ACK ${stepId} started/done`,
  )
}

/** Poll snapshot progress until download/extract stopped (replaces status.json snapshot poll). */
export async function waitPanelSnapshotStopped(
  target: AgentTarget,
  opts?: { timeoutMs?: number; onTick?: (st: StatusPayload) => void; allowSnapshot?: boolean },
): Promise<StatusPayload> {
  const nodeId = target.node
  if (!nodeId) {
    throw new Error('Node id required for snapshot stop ACK')
  }
  const timeoutMs = opts?.timeoutMs ?? 120_000
  const started = Date.now()
  let attempt = 0
  let lastSynthetic: StatusPayload = {} as StatusPayload

  while (Date.now() - started < timeoutMs) {
    attempt++
    const [wl, prog] = await Promise.all([
      fetchPanelNode(nodeId),
      api.nodeSnapshotProgress(nodeId).catch(() => null),
    ])
    lastSynthetic = workloadToStatusPayload(wl, opts?.allowSnapshot ?? true)
    if (prog) {
      lastSynthetic = {
        ...lastSynthetic,
        snapshot: {
          phase: prog.phase,
          detail: prog.detail,
          failed: prog.failed,
          error: prog.error,
          pct: prog.pct ?? undefined,
          ready: prog.ready,
        },
      } as StatusPayload
    }
    opts?.onTick?.(lastSynthetic)

    const phase = (prog?.phase || '').toLowerCase()
    if (phase === 'aborted' || prog?.status === 'aborted') {
      return lastSynthetic
    }
    if (!prog?.phase && !prog?.pct && !prog?.ready && (wl?.status || '') !== 'snapshot_running') {
      return lastSynthetic
    }
    if (prog?.ready || phase === 'idle' || phase === 'complete' || phase === 'done') {
      return lastSynthetic
    }

    await sleep(attempt < 4 ? 800 : 1500)
  }

  throw new Error(
    lastSynthetic.snapshot?.detail ||
      lastSynthetic.lifecycle?.detail ||
      'Snapshot did not stop in time — check node logs and retry',
  )
}
