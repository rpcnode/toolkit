import type { LifecycleInfo, LifecycleStep, StatusPayload } from '../types'
import { parseAria2PctFromText, parseSolanaDownloadPctFromText, pct } from './format'
import { isSolanaNetwork, resolveNetwork, supportsSnapshotStep } from './network'
import { isForeignChainDiskError } from './networkPaths'

/** Chain process fetches the archive itself (Agave / nitro --init.url). */
function snapshotViaNode(status?: StatusPayload | null, networkHint?: string | null): boolean {
  const p = status?.lifecycle?.profile
  if (p?.snapshot_via_node === true) return true
  if (String(p?.snapshot_bootstrap || '').toLowerCase() === 'via_node') return true
  return isSolanaNetwork(resolveNetwork(status, networkHint))
}

/** Drop snapshot bootstrap steps when the profile does not support them. */
export function filterLifecycleStepsForNetwork(
  steps: LifecycleStep[],
  status?: StatusPayload | null,
  networkHint?: string | null,
  panelNeedsSnapshot?: boolean,
): LifecycleStep[] {
  if (
    supportsSnapshotStep(status, networkHint, status?.view_env || status?.env, panelNeedsSnapshot)
  ) {
    return steps
  }
  return steps.filter((s) => {
    const id = (s.id || '').toLowerCase()
    const title = (s.title || '').toLowerCase()
    if (id === 'snapshot') return false
    if (title.includes('snapshot')) return false
    return true
  })
}

/** Resolved current lifecycle step — agent is source of truth. */
export type CurrentStepInfo = {
  id: string
  title: string
  status: string
  detail: string
  pct?: number | string
  /** 1-based index for display */
  index: number
  total: number
  /** e.g. "Step 3 of 5: Snapshot" */
  headline: string
  /** e.g. "Step 3 of 5" — badge only, title goes next to the node name */
  countLabel: string
}

/** Split "Step 5 of 5: CATCH-UP" → count + status. */
export function splitStepHeadline(raw: string | null | undefined): { count: string; status: string } {
  const t = (raw || '').trim()
  const m = t.match(/^step\s+(\d+)\s+of\s+(\d+)\s*[:—–-]\s*(.+)$/i)
  if (m) {
    return { count: `Step ${m[1]} of ${m[2]}`, status: m[3].trim() }
  }
  return { count: t, status: '' }
}

/**
 * Current step from agent lifecycle (current / steps / current_index).
 * Falls back to first active / first incomplete step; never invents a fake plan.
 */
export function resolveCurrentStep(
  lifecycle?: LifecycleInfo | null,
  steps?: LifecycleStep[] | null,
): CurrentStepInfo | null {
  const lc = lifecycle || null
  const list = (steps?.length ? steps : lc?.steps) || []
  if (!list.length && !lc?.current && !lc?.current_step_id && lc?.current_index == null) {
    return null
  }

  const total =
    typeof lc?.total_steps === 'number' && lc.total_steps > 0
      ? lc.total_steps
      : list.length || 0

  const failedId = (lc?.failed_step || '').toLowerCase()
  const wantId = (
    failedId ||
    lc?.current_step_id ||
    lc?.current ||
    lc?.current_step?.id ||
    ''
  ).toLowerCase()

  let idx = -1
  if (typeof lc?.current_index === 'number' && lc.current_index >= 0) {
    // Agent may send 0-based or 1-based; prefer matching id when possible.
    const zeroBased = lc.current_index < total ? lc.current_index : lc.current_index - 1
    if (zeroBased >= 0 && zeroBased < list.length) idx = zeroBased
  }
  if (idx < 0 && wantId) {
    idx = list.findIndex((s) => (s.id || '').toLowerCase() === wantId)
  }
  if (idx < 0) {
    // Prefer the latest active/error step (install must not win over snapshot/start).
    for (let i = list.length - 1; i >= 0; i--) {
      const s = list[i]
      if (s.active || s.status === 'active' || s.status === 'error') {
        idx = i
        break
      }
    }
  }
  if (idx < 0) {
    idx = list.findIndex((s) => s.status !== 'done' && s.status !== 'skipped' && !s.done)
  }
  if (idx < 0 && list.length) idx = list.length - 1
  if (idx < 0) return null

  const step = list[idx] || {}
  const id = step.id || wantId || lc?.phase || 'step'
  const title =
    step.title ||
    lc?.current_step?.title ||
    lc?.label ||
    id
  const status = step.status || lc?.current_step?.status || (step.active ? 'active' : 'pending')
  const detail = step.detail || lc?.current_step?.detail || lc?.detail || ''
  const stepPct = step.pct ?? lc?.current_step?.pct ?? lc?.pct
  const index1 = idx + 1
  const totalSafe = total > 0 ? total : index1
  const countLabel = `Step ${index1} of ${totalSafe}`
  const headline = `${countLabel}: ${title}`

  return {
    id,
    title,
    status,
    detail,
    pct: stepPct,
    index: index1,
    total: totalSafe,
    headline,
    countLabel,
  }
}

const LIFECYCLE_ORDER = ['ports', 'install', 'snapshot', 'start', 'ibd', 'run', 'healthy']

function stepOrderIndex(id: string): number {
  const i = LIFECYCLE_ORDER.indexOf(id.toLowerCase())
  return i >= 0 ? i : -1
}

/** Find a lifecycle step by id (case-insensitive). */
export function findLifecycleStep(
  status?: StatusPayload | null,
  stepId?: string,
): LifecycleStep | null {
  if (!status?.lifecycle?.steps?.length || !stepId) return null
  const want = stepId.toLowerCase()
  return status.lifecycle.steps.find((s) => (s.id || '').toLowerCase() === want) || null
}

/**
 * True when the **agent** has ACKed a lifecycle step (active/done + preferably timestamps).
 * Panel needs_provision shell / SQLite helpers never count as ACK.
 */
export function agentLifecycleStepAcked(
  status: StatusPayload | null | undefined,
  stepId: string,
): boolean {
  if (!status || status.needs_provision) return false
  const want = stepId.toLowerCase()
  const step = findLifecycleStep(status, want)
  const cur = (
    status.lifecycle?.current_step_id ||
    status.lifecycle?.current ||
    ''
  ).toLowerCase()

  if (step) {
    const st = (step.status || '').toLowerCase()
    if (st === 'error') return false
    const progressed =
      st === 'active' ||
      st === 'done' ||
      !!step.active ||
      !!step.done ||
      !!step.started_at ||
      !!step.finished_at
    if (progressed) return true
  }

  // Agent cursor already past this step → prior step is done.
  if (cur && cur !== want) {
    const a = stepOrderIndex(want)
    const b = stepOrderIndex(cur)
    if (a >= 0 && b > a) return true
  }

  return false
}

/** Map agent lifecycle.current → wizard step id (no SQLite invent). */
function syncFlag(v: unknown): boolean {
  return v === true || v === 1 || v === 'true' || v === '1'
}

/**
 * ONE truth with Sync badge «Synced»: no IBD, ~100% history proof.
 * Live tip / sync.ok + rpc up is not enough (XRPL full, Stellar healthy, BTC prune).
 * Must match panel leafHonestlySynced — if true, NODE SETUP must hide.
 * Explicit sync.ok=false (Sui tip-dead) never counts.
 */
function syncBlockTimeStale(iso?: string | null): boolean {
  const s = String(iso || '').trim()
  if (!s) return false
  const t = Date.parse(s)
  if (Number.isNaN(t)) return false
  return Date.now() - t > 3 * 60 * 1000
}

export function statusHonestlySynced(status: StatusPayload | null | undefined): boolean {
  if (!status || status.needs_provision) return false
  const sync = status.sync
  const rpc = status.rpc
  if (syncBlockTimeStale(sync?.block_time)) return false
  if (
    syncFlag(sync?.ibd) ||
    syncFlag(sync?.syncing) ||
    syncFlag(rpc?.syncing) ||
    syncFlag(rpc?.initialblockdownload)
  ) {
    return false
  }
  if (sync && sync.ok === false) return false
  const pct =
    typeof sync?.verification_pct === 'number'
      ? sync.verification_pct
      : typeof rpc?.verification_pct === 'number'
        ? rpc.verification_pct
        : null
  const rpcUp = !!(rpc?.ok || rpc?.reachable || rpc?.http_ok)
  const procUp = !!(rpc?.process_up || rpc?.port_open)
  if (pct != null && pct >= 99.9) {
    if (rpcUp || sync?.ok === true || procUp) return true
    if (typeof sync?.detail === 'string' && /^synced\b/i.test(sync.detail.trim())) return true
    const rpcSynced = (rpc as { synced?: boolean } | undefined)?.synced
    if (rpcSynced === true) return true
  }
  // Tip-only health (rpc up / sync.ok) is not Synced without ~100% history proof.
  return false
}

/** Restart/retry the chain unit after Install — including failed start (bad conf). */
export function nodeRestartAllowed(
  workload?: { status?: string; agent_port?: number } | null,
  phase?: string | null,
): boolean {
  const wl = (workload?.status || '').toLowerCase()
  if (['awaiting_ports', 'ready_to_install', 'ports_confirmed', 'removing'].includes(wl)) {
    return false
  }
  // Go tip leaf needs agent_port; Kotlin panel nodes stay at 0 after Start — use status.
  if (Number(workload?.agent_port || 0) <= 0 && !panelPastNodeSetup(workload)) return false
  const p = (phase || '').toLowerCase()
  if (p === 'restarting' || p === 'updating' || p === 'stopping' || p === 'starting') return false
  return true
}

/** Version is clickable except while a client update is in flight. Running nodes are fine. */
export function clientUpdateClickable(phase?: string | null): boolean {
  const p = (phase || '').toLowerCase()
  return p !== 'updating'
}

/**
 * Client update may start while the node is running — the host stops it as part of the job.
 * @deprecated Always true; kept for call sites that still pass `allowed`.
 */
export function clientUpdateAllowed(_phase?: string | null): boolean {
  return true
}

/** Soft-stop the chain unit. Start afterwards to bring it up. */
export function nodeStopAllowed(
  workload?: { status?: string; agent_port?: number } | null,
  phase?: string | null,
  nrPhase?: string | null,
): boolean {
  if (!nodeRestartAllowed(workload, phase)) return false
  if (nodeIsStopped(phase, nrPhase)) return false
  const p = (phase || '').toLowerCase()
  const nr = (nrPhase || '').toLowerCase()
  if (p === 'stopping' || nr === 'stopping') return false
  return true
}

/** Live Node Test — only while the fullnode is up (same gate as Stop). */
export function nodeLiveTestAllowed(
  workload?: { status?: string; agent_port?: number } | null,
  phase?: string | null,
  nrPhase?: string | null,
): boolean {
  return nodeStopAllowed(workload, phase, nrPhase)
}

export function nodeIsStopped(phase?: string | null, nrPhase?: string | null): boolean {
  const p = (phase || '').toLowerCase()
  const nr = (nrPhase || '').toLowerCase()
  return p === 'stopped' || nr === 'stopped'
}

/** Chain systemd unit is up (sync or caught up with tip). */
export function nodeProcessRunning(workload?: { status?: string } | null): boolean {
  const st = (workload?.status || '').toLowerCase()
  return st === 'sync' || st === 'active'
}

/** Panel SQLite statuses before chain start / snapshot bootstrap. */
const PANEL_EARLY_SETUP_STATUSES = new Set([
  'awaiting_ports',
  'ports_pending',
  'ports_confirmed',
  'ready_to_install',
  'needs_clients',
  'clients_error',
  'needs_snapshot',
  'snapshot_running',
])

/**
 * Panel SQLite row left early setup (starting / sync / active / errors).
 * Kotlin-managed nodes keep agent_port=0 (no Go leaf proxy) — do not require it.
 */
export function panelPastNodeSetup(
  workload?: { status?: string; agent_port?: number } | null,
): boolean {
  if (!workload) return false
  const st = (workload.status || '').toLowerCase()
  if (!st || PANEL_EARLY_SETUP_STATUSES.has(st)) return false
  return true
}

/** Ops shell when the panel has no live agent lifecycle feed. */
export function kotlinPanelOpsReady(
  status: StatusPayload | null | undefined,
  workload?: { status?: string; agent_port?: number } | null,
): boolean {
  if (status) return false
  if (!panelPastNodeSetup(workload)) return false
  const st = (workload?.status || '').toLowerCase()
  // Only fully online / stopped-after-setup — never hide NODE SETUP on snapshot_error / start_error / sync.
  return st === 'active' || st === 'online' || st === 'stopped'
}

/** Start the chain unit after Stop / client update. */
export function nodeStartAllowed(
  workload?: { status?: string; agent_port?: number } | null,
  phase?: string | null,
  nrPhase?: string | null,
): boolean {
  if (!nodeRestartAllowed(workload, phase)) return false
  return nodeIsStopped(phase, nrPhase)
}

/** True when ops UI (not install wizard) should take over — Healthy / connect.ready. */
export function nodeReadyForOps(status: StatusPayload | null | undefined): boolean {
  if (!status) return false
  // An official snapshot ExtraStep still fetching means the dataset is
  // incomplete no matter what RPC/connect momentarily reports (java-tron/
  // op-reth/etc. can already answer requests against a half-extracted
  // datadir while wget|tar keeps writing) — every network with a Snapshot
  // step must keep NODE SETUP visible until that download actually finishes.
  if (snapshotDownloadLive(status)) return false
  if (status.connect?.ready === true) return true
  // SYNCED ⇒ ops shell (never NODE SETUP alongside Sync badge Synced).
  if (statusHonestlySynced(status)) return true
  const sync = status.sync as { ibd?: unknown; syncing?: unknown } | undefined
  const rpc = status.rpc as { initialblockdownload?: unknown; syncing?: unknown } | undefined
  // BSC/eth put syncing on sync.syncing; bitcoin uses sync.ibd / rpc.initialblockdownload.
  if (
    syncFlag(sync?.ibd) ||
    syncFlag(sync?.syncing) ||
    syncFlag(rpc?.initialblockdownload) ||
    syncFlag(rpc?.syncing)
  ) {
    return false
  }
  const phase = (status.ui_phase || status.lifecycle?.phase || '').toLowerCase()
  const ns = (status.node_status || status.lifecycle?.node_status || '').toLowerCase()
  const cur = (
    status.lifecycle?.current_step_id ||
    status.lifecycle?.current ||
    ''
  ).toLowerCase()
  const label = (status.lifecycle?.label || '').toLowerCase()
  // Agent ACK healthy/running wins over current=run (last step id stays "run" when complete).
  if (phase === 'healthy' || ns === 'running' || ns === 'healthy' || ns === 'active' || cur === 'healthy') {
    // False Healthy (Sui checkpoint 0 / tip probe dead): rpc.ok but sync.ok=false
    // must keep NODE SETUP / sync rail — not ops shell.
    if (status.sync && status.sync.ok === false) return false
    // L2/HL agents report rpc.ok; bitcoin uses reachable/http_ok.
    return !!(status.rpc?.reachable || status.rpc?.http_ok || status.rpc?.ok)
  }
  // Still in lifecycle / syncing — keep setup wizard with left steps.
  if (
    phase === 'run' ||
    ns === 'syncing' ||
    ns === 'sync' ||
    cur === 'run' ||
    cur === 'ibd' ||
    phase === 'ports' ||
    phase === 'install' ||
    phase === 'snapshot' ||
    phase === 'start' ||
    phase === 'setup' ||
    label.includes('sync')
  ) {
    return false
  }
  return false
}

/** Official snapshot ExtraStep still fetching (aria2 / wget) — not done. */
export function snapshotDownloadLive(status: StatusPayload | null | undefined): boolean {
  const snap = status?.snapshot
  const ns = (status?.node_status || status?.lifecycle?.node_status || '').toLowerCase()
  const phase = (snap?.phase || '').toLowerCase()
  // Dead unit / start_error must not look like a live fetch (leftover downloaded N%).
  if (
    snap?.failed ||
    phase === 'error' ||
    phase === 'aborted' ||
    snap?.aborted ||
    ns === 'start_error' ||
    ns.includes('snapshot_error') ||
    (Boolean(status?.start_error) && !snap?.busy)
  ) {
    return false
  }
  // Panel already past Snapshot (unit started / catching up) — not ExtraStep live.
  if (ns === 'sync' || ns === 'syncing' || ns === 'starting' || ns === 'active' || ns === 'online') {
    return !!(snap?.busy || snap?.wget_running)
  }
  if (snap?.busy || snap?.wget_running) return true
  if (phase === 'download' || phase === 'extract' || phase === 'extracting') return true
  if (ns.includes('snapshot_download') || ns.includes('snapshot_extract')) return true
  const cur = (
    status?.lifecycle?.current_step_id ||
    status?.lifecycle?.current ||
    ''
  ).toLowerCase()
  if (cur === 'snapshot' && snap?.ready !== true) {
    // Lifecycle still on Snapshot after operator Stop — pending, not downloading.
    if (
      !snap?.busy &&
      !snap?.wget_running &&
      (snap?.can_start || ns === 'needs_snapshot')
    ) {
      return false
    }
    return true
  }
  const chunks: string[] = []
  if (snap?.detail) chunks.push(String(snap.detail))
  if (status?.lifecycle?.detail) chunks.push(String(status.lifecycle.detail))
  const tails = status?.sync?.log_tail || status?.logs?.lines
  if (Array.isArray(tails)) chunks.push(tails.join('\n'))
  const blob = chunks.join('\n')
  const aria = parseAria2PctFromText(blob)
  if (aria != null && aria > 0 && aria < 99.9) return true
  // Agave `solana_file_download` runs after Start (via-node) — Sync progress, not Snapshot ExtraStep.
  if (snapshotViaNode(status)) return false
  const sol = parseSolanaDownloadPctFromText(blob)
  return sol != null && sol > 0 && sol < 99.9
}

/** Leaf official snapshot ACK — tip host_tip has enabled=false / ready=true. */
export function snapReady(status: StatusPayload | null | undefined): boolean {
  const snap = status?.snapshot
  if (!snap || !status) return false
  if (status.host_tip) return false
  if (snap.enabled === false) return false
  // Stale marker / first CSV part must not hide the download bar.
  if (snapshotDownloadLive(status)) return false
  return !!snap.ready
}

export function wizardStepFromAgentLifecycle(
  status: StatusPayload | null | undefined,
  allowSnapshot: boolean,
): 'ports' | 'install' | 'snapshot' | 'start' | 'sync' | 'done' | null {
  if (!status || status.needs_provision) {
    // Structural shell always ports until per-node agent answers.
    return status?.needs_provision ? 'ports' : null
  }
  // Failed lane step wins — do not hide Snapshot/Start error behind Install.
  const failed = (status.lifecycle?.failed_step || '').toLowerCase()
  if (failed === 'ports' || failed === 'install') return failed
  if (failed === 'fetch' || failed === 'preflight') return 'install'
  if (failed === 'snapshot' && allowSnapshot) return 'snapshot'
  if (failed === 'start' || failed === 'verify' || failed === 'cleanup') {
    return 'start'
  }
  if (failed === 'run' || failed === 'ibd') {
    return 'sync'
  }
  // Panel SQLite is source of truth once the unit is past Snapshot (Solana via-node
  // never sets snapshot.ready — !snapReady used to pin the rail on Snapshot forever).
  const ns = (status.node_status || status.lifecycle?.node_status || '').toLowerCase()
  if (ns === 'sync' || ns === 'syncing') return 'sync'
  if (ns === 'active' || ns === 'online' || ns === 'running') {
    return nodeReadyForOps(status) ? 'done' : 'sync'
  }
  if (ns === 'starting' || ns === 'start_error') return 'start'
  // complete + Healthy → done; complete while still syncing → Sync step.
  if (status.lifecycle?.complete && nodeReadyForOps(status)) return 'done'
  const id = (
    status.lifecycle?.current_step_id ||
    status.lifecycle?.current ||
    resolveCurrentStep(status.lifecycle)?.id ||
    ''
  ).toLowerCase()
  if (!id) {
    if (status.lifecycle?.complete) return nodeReadyForOps(status) ? 'done' : 'sync'
    return null
  }
  if (id === 'ports') return 'ports'
  if (id === 'install' || id === 'fetch' || id === 'preflight') return 'install'
  if (id === 'snapshot') return allowSnapshot ? 'snapshot' : 'start'
  if (id === 'verify' || id === 'cleanup') return 'start'
  const needOfficialSnap =
    allowSnapshot &&
    !snapshotViaNode(status) &&
    !snapReady(status) &&
    !status.snapshot?.failed
  if (id === 'start') {
    if (needOfficialSnap) return 'snapshot'
    return 'start'
  }
  if (id === 'ibd') {
    if (needOfficialSnap) return 'snapshot'
    return 'sync'
  }
  // run / healthy: Sync until ready for ops, then Finish.
  if (id === 'run' || id === 'healthy') return nodeReadyForOps(status) ? 'done' : 'sync'
  return null
}

export type NodePhase =
  | 'setup'
  | 'installing'
  | 'starting'
  | 'updating'
  | 'removing'
  | 'restarting'
  | 'stopping'
  | 'stopped'
  | 'syncing'
  | 'working'
  | 'error'
  | 'unknown'

export type NodeLifecycle = {
  phase: NodePhase
  /** Short badge text */
  label: string
  /** One-line detail under the title */
  detail: string
  color: string
  /** Show spinner while not working */
  busy: boolean
  progress?: number
  height?: number | null
}

/** Warmup disk estimate (unpack needs · free Y) — not a snapshot/install fault. */
export function isSolanaDiskProjection(text: string | null | undefined): boolean {
  const low = String(text || '').toLowerCase()
  if (!low) return false
  if (
    low.includes('will not fit') ||
    low.includes('no space') ||
    low.includes('enospc') ||
    low.includes('os error 28') ||
    low.includes('unpack error') ||
    low.includes('failed to unpack')
  ) {
    return false
  }
  if (low.includes('warming up')) return true
  return low.includes('unpack needs') && low.includes('free')
}

/** Agent-reported snapshot gate / download failure (disk, unit, extract). */
export function snapshotBlockMessage(
  status: StatusPayload | null | undefined,
): string {
  if (!status) return ''
  const snap = status.snapshot
  const ns = (status.lifecycle?.node_status || '').toLowerCase()
  const phase = (status.lifecycle?.phase || '').toLowerCase()
  const snapPhase = (snap?.phase || '').toLowerCase()
  const blob = [
    status.lifecycle?.detail,
    status.lifecycle?.label,
    snap?.error,
    snap?.detail,
  ]
    .filter(Boolean)
    .join(' ')
  const msg = String(
    snap?.error || snap?.detail || status.lifecycle?.detail || '',
  ).trim()
  if (isSolanaDiskProjection(msg) || isSolanaDiskProjection(blob)) return ''
  const failed =
    !!snap?.failed ||
    ns === 'snapshot_error' ||
    snapPhase === 'error' ||
    (phase === 'error' && /snapshot|insufficient disk/i.test(blob))
  if (!failed) return ''
  return msg || 'Snapshot failed'
}

/** Foreign chain disk/snapshot copy — paths from internal/networks/<id>. */
export function isForeignTronDiskError(
  text: string | null | undefined,
  network?: string | null,
): boolean {
  return isForeignChainDiskError(text, network)
}

/** Map agent lifecycle.phase → UI NodePhase. */
function mapAgentPhase(
  phase?: string,
  nodeStatus?: string,
  opts?: { noSnapshot?: boolean },
): NodePhase | null {
  const p = (phase || '').toLowerCase()
  const ns = (nodeStatus || '').toLowerCase()
  if (p === 'error' || ns === 'paused') return 'error'
  // No-snapshot profiles (bitcoin / capabilities): never map TRON snapshot → installing.
  if (opts?.noSnapshot) {
    if (
      p === 'snapshot' ||
      ns === 'snapshot_download' ||
      ns === 'snapshot_extract' ||
      ns === 'needs_snapshot' ||
      ns === 'waiting_snapshot' ||
      ns === 'snapshot_error' ||
      ns === 'snapshot_running'
    ) {
      return 'starting'
    }
  } else if (ns === 'snapshot_error') {
    return 'error'
  }
  if (
    !opts?.noSnapshot &&
    (p === 'snapshot' ||
      ns === 'snapshot_download' ||
      ns === 'snapshot_extract' ||
      ns === 'needs_snapshot' ||
      ns === 'waiting_snapshot')
  ) {
    return 'installing'
  }
  if (ns === 'ready_to_start') return 'setup'
  if (p === 'updating') return 'updating'
  if (p === 'restarting') return 'restarting'
  if (p === 'stopping') return 'stopping'
  if (p === 'stopped') return 'stopped'
  if (p === 'start' || ns === 'starting') return 'starting'
  if (p === 'run' || ns === 'syncing' || ns === 'sync') return 'syncing'
  if (p === 'healthy' || ns === 'running' || ns === 'active') return 'working'
  if (p === 'ports' || p === 'install' || p === 'setup' || ns === 'installing' || ns === 'awaiting_ports') {
    return 'setup'
  }
  return null
}

export function deriveLifecycleSteps(
  status?: StatusPayload | null,
  workloadStatus?: string | null,
  networkHint?: string | null,
  panelNeedsSnapshot?: boolean,
): LifecycleStep[] {
  const allowSnap = supportsSnapshotStep(status, networkHint, undefined, panelNeedsSnapshot)

  if (status?.lifecycle?.steps?.length) {
    return filterLifecycleStepsForNetwork(
      status.lifecycle.steps,
      status,
      networkHint,
      panelNeedsSnapshot,
    )
  }
  if (status?.setup?.lifecycle_steps?.length) {
    return filterLifecycleStepsForNetwork(
      status.setup.lifecycle_steps,
      status,
      networkHint,
      panelNeedsSnapshot,
    )
  }

  const snap = status?.snapshot
  const apiUp = !!(status?.checks?.api_healthz || status?.checks?.api_port_open)
  const registered = !!(status?.instance as { registered?: boolean } | undefined)?.registered
  const marker = !!snap?.ready
  const busy =
    allowSnap &&
    (!!snap?.wget_running ||
      ['download', 'extract', 'extracting'].includes((snap?.phase || '').toLowerCase()))
  const failed =
    allowSnap &&
    (!!snap?.failed || (workloadStatus || '').toLowerCase() === 'snapshot_error')
  const online = !!(status?.rpc?.reachable || status?.rpc?.http_ok)
  const processUp = !!(
    status?.rpc?.process_up ||
    status?.checks?.java_tron_process ||
    status?.checks?.node_process_up
  )
  const height = status?.rpc?.node_height
  const catching = online && (height == null || Number(height) < 1000)
  // Past install once snapshot/start has begun — avoid flashing Install as current.
  const pastInstall = marker || busy || failed || online || processUp
  const installDone = (registered && apiUp) || pastInstall

  const step = (
    id: string,
    title: string,
    statusName: LifecycleStep['status'],
    detail: string,
    p?: number | string,
  ): LifecycleStep => ({
    id,
    title,
    status: statusName,
    // skipped ≠ done (UI must not show a checkmark for intentionally skipped steps)
    done: statusName === 'done',
    active: statusName === 'active',
    error: statusName === 'error',
    detail,
    pct: p,
  })

  // Fallback only when agent lifecycle.steps is missing — still respect snapshot gate (TRON).
  const snapBlocksStart =
    allowSnap && !marker && (snap?.enabled !== false || busy || failed)
  const startStatus: LifecycleStep['status'] = snapBlocksStart
    ? 'pending'
    : online
      ? 'done'
      : processUp
        ? 'active'
        : 'pending'
  const runStatus: LifecycleStep['status'] = !online || snapBlocksStart
    ? 'pending'
    : catching
      ? 'active'
      : 'done'

  // Fallback only when agent omitted steps — keep network-neutral; agent owns chain-specific copy.
  const steps: LifecycleStep[] = [
    step(
      'install',
      'Install',
      installDone ? 'done' : 'active',
      installDone ? 'Agent ready' : 'Provision agent',
    ),
  ]

  if (allowSnap) {
    steps.push(
      step(
        'snapshot',
        'Snapshot',
        failed ? 'error' : marker ? 'done' : busy ? 'active' : 'pending',
        failed
          ? snap?.error || snap?.detail || 'Snapshot failed'
          : marker
            ? 'Chain data ready'
            : busy
              ? `Snapshot ${snap?.phase || 'download'}`
              : 'Waiting for snapshot',
        marker ? 100 : snap?.pct,
      ),
    )
  }

  steps.push(
    step(
      'start',
      'Start',
      startStatus,
      online
        ? 'RPC responding'
        : processUp
          ? 'Warming up'
          : snapBlocksStart
            ? 'Waiting for snapshot'
            : 'Starting node',
    ),
    step(
      'run',
      'Run',
      runStatus,
      !online || snapBlocksStart
        ? 'Waiting for RPC'
        : catching
          ? height != null
            ? `Syncing · height ${height}`
            : 'Syncing'
          : height != null
            ? `Healthy · height ${height}`
            : 'Healthy',
    ),
  )

  return steps
}

function processUp(status: StatusPayload): boolean {
  const rpc = status.rpc || {}
  const checks = status.checks || {}
  return (
    !!rpc.process_up ||
    !!checks.java_tron_process ||
    !!checks.node_process_up ||
    status.services?.node === 'active' ||
    status.services?.node === 'activating'
  )
}

function nodeOnline(status: StatusPayload): boolean {
  return !!(status.rpc?.reachable || status.rpc?.http_ok || status.connect?.ready)
}

function snapBusy(snap: StatusPayload['snapshot']): boolean {
  if (!snap) return false
  if (snap.failed) return false
  return (
    !!snap.wget_running ||
    ['download', 'extract', 'extracting'].includes((snap.phase || '').toLowerCase())
  )
}

/**
 * Lifecycle for list/detail:
 * setup (ports / wait for Install) → installing (snapshot) → starting → catching up → working.
 *
 * Do NOT show «installing» until the user confirmed ports and clicked Install
 * (or snapshot is actually running).
 */
export function deriveNodeLifecycle(
  status: StatusPayload | null | undefined,
  workloadStatus?: string | null,
  network?: string | null,
  panelNeedsSnapshot?: boolean,
): NodeLifecycle {
  const wl = (workloadStatus || '').toLowerCase()
  const allowSnap = supportsSnapshotStep(status, network, undefined, panelNeedsSnapshot)
  const noSnapshot = !allowSnap
  const snap = status?.snapshot
  const height = status?.rpc?.node_height ?? status?.lifecycle?.height ?? null
  const health = (status?.health || '').toLowerCase()
  const uiPhase = (status?.ui_phase || '').toLowerCase()
  const busySnap = allowSnap && (snapBusy(snap) || snapshotDownloadLive(status))
  const agentErr = status?.agent?.last_error || status?.api_agent?.last_error || ''
  const lc = status?.lifecycle
  const cu = status?.client_update
  const cuPhase = (cu?.phase || '').toLowerCase()
  const nr = status?.node_restart
  const nrPhase = (nr?.phase || '').toLowerCase()
  if (cuPhase === 'updating') {
    return {
      phase: 'updating',
      label: 'Updating client',
      detail: cu?.detail || 'Replacing client',
      color: 'yellow',
      busy: true,
      progress: typeof cu?.pct === 'number' ? cu.pct : undefined,
      height: height == null ? null : Number(height),
    }
  }
  if (nrPhase === 'stopping') {
    return {
      phase: 'stopping',
      label: 'Soft-stopping node',
      detail: nr?.detail || 'Public RPC sleeping (503) during stop',
      color: 'yellow',
      busy: true,
      progress: typeof nr?.pct === 'number' ? nr.pct : undefined,
      height: height == null ? null : Number(height),
    }
  }
  if (nrPhase === 'stopped') {
    return {
      phase: 'stopped',
      label: 'Stopped',
      detail: nr?.detail || 'Fullnode stopped — Start to start',
      color: 'gray',
      busy: false,
      height: height == null ? null : Number(height),
    }
  }
  if (nrPhase === 'restarting' || nrPhase === 'starting') {
    return {
      phase: nrPhase === 'starting' ? 'starting' : 'restarting',
      label: nrPhase === 'starting' ? 'Starting node' : 'Restarting node',
      detail: nr?.detail || 'Public RPC sleeping (503) during restart',
      color: 'yellow',
      busy: true,
      progress: typeof nr?.pct === 'number' ? nr.pct : undefined,
      height: height == null ? null : Number(height),
    }
  }
  if (cuPhase === 'starting') {
    return {
      phase: 'updating',
      label: 'Starting after client update',
      detail: cu?.detail || 'Public RPC sleeping (503) during client update',
      color: 'yellow',
      busy: true,
      progress: typeof cu?.pct === 'number' ? cu.pct : undefined,
      height: height == null ? null : Number(height),
    }
  }

  // Host Server agent answering for an unprovisioned node → setup, not mismatch.
  if (status?.needs_provision) {
    return {
      phase: 'installing',
      label: lc?.label || 'Setup',
      detail: lc?.detail || 'Install to check ports on the host',
      color: 'yellow',
      busy: false,
      height: height == null ? null : Number(height),
    }
  }

  // Real mismatch only when panel flagged a truly incompatible binary.
  // Soften copy — short detail from agent/panel, no lectures.
  const mismatch =
    !!status?.network_mismatch ||
    health === 'mismatch' ||
    (lc?.node_status || '').toLowerCase() === 'network_mismatch'
  if (mismatch) {
    const detail =
      lc?.detail || status?.message || status?.note || 'incompatible agent'
    return {
      phase: 'error',
      label: 'Wrong agent',
      detail,
      color: 'red',
      busy: false,
      height: height == null ? null : Number(height),
    }
  }

  // Prefer agent lifecycle (source of truth) — render label/detail as returned.
  // Drop foreign TRON disk/snapshot_error when this page is another network.
  const foreignTron =
    !!network &&
    isForeignTronDiskError(
      [
        lc?.detail,
        lc?.label,
        lc?.node_status,
        snap?.error,
        snap?.detail,
        agentErr,
        status?.message,
        status?.note,
      ].join(' '),
      network,
    )
  if (foreignTron) {
    return {
      phase: 'syncing',
      label: 'syncing',
      detail: '',
      color: 'cyan',
      busy: true,
      height: height == null ? null : Number(height),
    }
  }

  const mapped = mapAgentPhase(lc?.phase || uiPhase, lc?.node_status || status?.node_status, {
    noSnapshot,
  })
  if (mapped && lc) {
    const label = lc.label || mapped
    const detail = lc.detail || ''
    const color =
      mapped === 'working'
        ? 'teal'
        : mapped === 'error'
          ? 'red'
          : mapped === 'installing'
            ? 'yellow'
            : mapped === 'syncing'
              ? 'cyan'
              : mapped === 'starting'
                ? 'yellow'
                : 'gray'
    return {
      phase: mapped,
      label,
      detail,
      color,
      busy: !!lc.busy || mapped === 'installing' || mapped === 'starting' || mapped === 'syncing',
      progress: pct(noSnapshot ? lc.pct : (lc.pct ?? snap?.pct)),
      height: height == null ? null : Number(height),
    }
  }

  if (status?.maintenance?.enabled || status?.pause?.active) {
    return {
      phase: 'error',
      label: 'paused',
      detail: status.pause?.message || status.maintenance?.reason || 'RPC paused',
      color: 'yellow',
      busy: false,
      height,
    }
  }

  // Agent down with no cached lifecycle — only then fall back to error shell.
  // When status carries last-known lifecycle/rpc (panel cache), keep rendering it.
  const agentUnreachable =
    status?.agent_reachable === false ||
    status?.error === 'agent_unreachable' ||
    (status?.agent?.status === 'error' && status?.agent?.activity === 'unreachable')
  if (
    (wl === 'agent_error' || agentUnreachable) &&
    !lc &&
    height == null &&
    !status?.sync &&
    !status?.logs?.lines?.length
  ) {
    return {
      phase: 'error',
      label: 'agent error',
      detail: agentErr || 'Agent unreachable',
      color: 'red',
      busy: false,
      height,
    }
  }

  if (allowSnap && (snap?.failed || wl === 'snapshot_error')) {
    return {
      phase: 'error',
      label: 'snapshot error',
      detail: snap?.error || snap?.detail || agentErr || 'Retry snapshot in setup wizard',
      color: 'red',
      busy: false,
      progress: pct(snap?.pct),
      height,
    }
  }

  if (wl === 'start_error') {
    return {
      phase: 'error',
      label: 'start error',
      detail: agentErr || 'Node start failed — retry in setup wizard',
      color: 'red',
      busy: false,
      height,
    }
  }

  if (wl === 'removing') {
    return {
      phase: 'removing',
      label: 'removing',
      detail: 'Wiping host folders — row drops when they are gone',
      color: 'orange',
      busy: true,
      height,
    }
  }

  if (wl === 'remove_error') {
    return {
      phase: 'error',
      label: 'remove error',
      detail: 'Wipe did not finish — retry Remove (tip resumes kill → units → wipe)',
      color: 'red',
      busy: false,
      height,
    }
  }

  // Real install in progress (user clicked Install / snapshot downloading).
  if (allowSnap && (busySnap || wl === 'snapshot_running')) {
    const p = pct(snap?.pct)
    return {
      phase: 'installing',
      label: busySnap ? `installing ${String(snap?.pct ?? '…')}` : 'installing',
      detail: busySnap
        ? `Snapshot ${snap?.phase || 'download'}${snap?.eta ? ` · ETA ${snap.eta}` : ''}`
        : 'Starting snapshot download…',
      color: 'yellow',
      busy: true,
      progress: Number.isFinite(p) ? p : 8,
      height,
    }
  }

  // Setup gate: prefer agent lifecycle detail; SQLite ports_confirmed is UX helper only.
  const awaitingSetup =
    wl === 'awaiting_ports' ||
    wl === 'ports_pending' ||
    wl === 'provisioned' ||
    (allowSnap && wl === 'needs_snapshot') ||
    wl === 'ports_confirmed' ||
    wl === 'ready_to_install' ||
    wl === '' ||
    wl === 'setup'

  if (awaitingSetup && (noSnapshot || !snap?.ready)) {
    const agentDetail =
      status?.lifecycle?.detail ||
      resolveCurrentStep(status?.lifecycle)?.detail ||
      ''
    const cur = (
      status?.lifecycle?.current_step_id ||
      status?.lifecycle?.current ||
      ''
    ).toLowerCase()
    if (cur === 'install' || agentLifecycleStepAcked(status, 'ports')) {
      return {
        phase: 'setup',
        label: status?.lifecycle?.label || 'setup',
        detail: agentDetail || 'Waiting for Install (agent)',
        color: 'cyan',
        busy: !!status?.lifecycle?.busy,
        height,
      }
    }
    return {
      phase: 'setup',
      label: status?.lifecycle?.label || 'setup',
      detail: agentDetail || 'Install to check catalog ports, then continue setup',
      color: 'gray',
      busy: !!status?.lifecycle?.busy,
      height,
    }
  }

  if (
    wl === 'starting' ||
    (noSnapshot && (wl === 'snapshot_running' || wl === 'needs_snapshot')) ||
    (allowSnap && snap?.ready && (!status || !nodeOnline(status)))
  ) {
    if (status && !nodeOnline(status) && (processUp(status) || !!status.rpc?.port_open)) {
      return {
        phase: 'starting',
        label: lc?.label || 'starting',
        detail: lc?.detail || 'Warming up · waiting for RPC',
        color: 'yellow',
        busy: true,
        height,
      }
    }
    return {
      phase: 'starting',
      label: lc?.label || 'starting',
      detail:
        lc?.detail ||
        (wl === 'starting'
          ? 'Start requested · waiting for process'
          : allowSnap
            ? 'Snapshot ready · start node'
            : 'Starting node'),
      color: 'yellow',
      busy: true,
      height,
    }
  }

  if (status && nodeOnline(status)) {
    const h = height == null ? null : Number(height)
    const early = h != null && h > 0 && h < 1000
    const catching =
      early || health === 'setup' || health === 'degraded' || uiPhase === 'setup' || uiPhase === 'sync'

    if (catching) {
      return {
        phase: 'syncing',
        label: lc?.label || 'catching up',
        detail:
          lc?.detail ||
          (h != null ? `Height ${h} · syncing with network` : 'RPC up · syncing with network'),
        color: 'cyan',
        busy: true,
        height: h,
      }
    }

    return {
      phase: 'working',
      label: 'working',
      detail: h != null ? `Height ${h} · online` : 'Node online',
      color: 'teal',
      busy: false,
      height: h,
    }
  }

  if (wl === 'online') {
    return {
      phase: 'working',
      label: 'working',
      detail: 'Marked online',
      color: 'teal',
      busy: false,
    }
  }

  if (!status && (wl === 'agent_error' || wl === '')) {
    return {
      phase: 'error',
      label: 'agent error',
      detail: 'Agent unreachable',
      color: 'red',
      busy: false,
    }
  }

  if (!status && wl === 'stopped') {
    return {
      phase: 'stopped',
      label: 'stopped',
      detail: 'Chain unit stopped',
      color: 'gray',
      busy: false,
      height: height == null ? null : Number(height),
    }
  }

  if (!status && (wl === 'sync' || wl === 'active')) {
    const h = height == null ? null : Number(height)
    return {
      phase: wl === 'active' ? 'working' : 'syncing',
      label: wl === 'active' ? 'active' : 'sync',
      detail: h != null ? `Height ${h.toLocaleString()}` : 'Node process running on host',
      color: wl === 'active' ? 'teal' : 'cyan',
      busy: wl === 'sync',
      height: h,
    }
  }

  if (!status && (wl === 'start_error' || wl === 'snapshot_error')) {
    return {
      phase: 'error',
      label: wl.replace(/_/g, ' '),
      detail: 'See node log in the details panel',
      color: 'red',
      busy: false,
      height: height == null ? null : Number(height),
    }
  }

  // Cached / degraded payload without a mappable phase — keep height if any.
  if (agentUnreachable && (height != null || status?.cached)) {
    return {
      phase: mapped || 'unknown',
      label: lc?.label || wl || 'cached',
      detail: lc?.detail || 'Last known status (agent unreachable)',
      color: 'gray',
      busy: false,
      height: height == null ? null : Number(height),
    }
  }

  return {
    phase: 'unknown',
    label: wl || 'unknown',
    detail: status ? 'Waiting for status…' : 'Agent unreachable',
    color: status ? 'gray' : 'red',
    busy: false,
  }
}
