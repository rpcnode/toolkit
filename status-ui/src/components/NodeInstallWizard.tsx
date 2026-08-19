import {
  ActionIcon,
  Alert,
  Badge,
  Box,
  Button,
  Code,
  Group,
  Loader,
  Modal,
  Progress,
  Skeleton,
  Stack,
  Text,
  ThemeIcon,
  Title,
  Tooltip,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import {
  IconAlertTriangle,
  IconCheck,
  IconCopy,
  IconDownload,
  IconLockOpen,
  IconPlayerPlay,
  IconRocket,
  IconServer,
  IconX,
} from '@tabler/icons-react'
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import {
  api,
  type CheckedCatalogPort,
  type AgentTarget,
  type DiskRoleDef,
  type HostDiskInfo,
  type HostDiskInsight,
  type HostMountInfo,
  type MultiDiskLayoutPlan,
  type RegistryNode,
  type Workload,
} from '../api'
import type { StatusPayload } from '../types'
import { CopyMaskedUrl } from './CopyMaskedUrl'
import { copyText } from '../lib/copyText'
import { agentVersionOutdated } from '../lib/agentVersion'
import { formatSyncPct, pct } from '../lib/format'
import { maskHostname } from '../lib/maskHost'
import {
  agentLifecycleStepAcked,
  nodeReadyForOps,
  snapshotBlockMessage,
  statusHonestlySynced,
  resolveCurrentStep,
  wizardStepFromAgentLifecycle,
} from '../lib/nodeLifecycle'
import { isXrplNetwork, supportsIbdStep, supportsSnapshotStep } from '../lib/network'
import { agentLogLines } from './AgentLogsPanel'
import { DiskLayoutPanel } from './DiskLayoutPanel'
import { HostDiskInsights } from './HostDiskInsights'
import {
  XrplHistoryPicker,
  xrplHistoryInstallLabel,
  type XrplHistoryMode,
} from './XrplHistoryPicker'
import {
  InstallOptionsPicker,
  fallbackInstallGroups,
  installOptionLabel,
  parseInstallOptionGroups,
  type InstallOptionGroup,
} from './InstallOptionsPicker'
import { resolveSyncProgressPct } from './SyncStatusCard'
import { InstallProgressModal, type InstallProgressOutcome } from './InstallProgressModal'

/** Networks with tip multi_disk_roles (must match api-agent/disk_roles.go). */
const MULTI_DISK_NETWORKS = new Set([
  'solana',
  'ethereum',
  'bsc',
  'arb',
  'robinhood',
  'base',
  'optimism',
  'tron',
  'ton',
  'sui',
  'aptos',
  'avalanche',
  'bitcoin',
  'ltc',
  'dash',
  'bch',
])

type UnsupportedCapability = {
  error: 'unsupported_network' | 'unsupported_env'
  message: string
  agentVersion: string
}

function detectUnsupportedCapability(res: {
  error?: string
  message?: string
  hint?: string
  agent_version?: string
  agent?: { error?: string; message?: string; agent_version?: string; version?: string }
}): UnsupportedCapability | null {
  const nested = res.agent || {}
  const code = String(res.error || nested.error || '').toLowerCase()
  const msg = String(res.message || nested.message || '')
  const low = msg.toLowerCase()
  const agentVersion = String(
    res.agent_version || nested.agent_version || nested.version || '',
  ).trim()
  const capabilityHint =
    res.hint === 'update_agent' &&
    code !== 'provision_failed' &&
    code !== 'plan_failed' &&
    code !== 'host_deps_failed'
  const isUnsupported =
    code === 'unsupported_network' ||
    code === 'unsupported_env' ||
    capabilityHint ||
    low.includes('no canonical ports for') ||
    low.startsWith('supported:') ||
    (low.includes('not supported by this agent') && low.includes('update'))
  if (!isUnsupported) return null
  return {
    error: code === 'unsupported_env' ? 'unsupported_env' : 'unsupported_network',
    message: msg || 'Network/environment is not supported by this agent.',
    agentVersion,
  }
}

function sleep(ms: number) {
  return new Promise<void>((r) => setTimeout(r, ms))
}

/** Poll status.json until agent ACKs a lifecycle step (or timeout). */
async function waitAgentLifecycleAck(
  target: AgentTarget,
  stepId: string,
  opts?: {
    timeoutMs?: number
    onTick?: (st: StatusPayload) => void
    /** Also accept if agent current is one of these (past the step). */
    acceptCurrent?: string[]
  },
): Promise<StatusPayload> {
  const timeoutMs = opts?.timeoutMs ?? 60_000
  const deadline = Date.now() + timeoutMs
  let last: StatusPayload | null = null
  let attempt = 0
  while (Date.now() < deadline) {
    attempt++
    last = await api.status(target, { live: true })
    opts?.onTick?.(last)
    const snapBlock = snapshotBlockMessage(last)
    if (snapBlock) {
      throw new Error(snapBlock)
    }
    if (agentLifecycleStepAcked(last, stepId)) return last
    const cur = (
      last.lifecycle?.current_step_id ||
      last.lifecycle?.current ||
      ''
    ).toLowerCase()
    if (cur && opts?.acceptCurrent?.some((c) => c.toLowerCase() === cur)) {
      return last
    }
    // Back off a bit after first few quick polls (unit activation ~600ms).
    await sleep(attempt < 4 ? 800 : 1500)
  }
  const detail =
    last?.lifecycle?.detail ||
    last?.lifecycle?.steps?.find((s) => (s.id || '').toLowerCase() === stepId.toLowerCase())
      ?.detail ||
    last?.message ||
    ''
  throw new Error(
    detail
      ? `Agent did not ACK ${stepId}: ${detail}`
      : `Agent did not ACK ${stepId} started/done`,
  )
}

export type WizardStepId = 'ports' | 'install' | 'snapshot' | 'start' | 'done'

function formatPortBusy(check: {
  message?: string
  error?: string
  busy_ports?: { port?: number; role?: string; holder?: string }[]
}): string {
  const busy = (check.busy_ports || [])
    .map((b) => {
      const who = b.holder === 'host_tip' ? 'host tip' : b.holder || 'foreign'
      return `${b.role || 'port'} :${b.port} (${who})`
    })
    .join(', ')
  if (check.message && busy) return `${check.message} — ${busy}`
  return check.message || check.error || (busy ? `Ports busy: ${busy}` : 'port_busy')
}

/** SSH on the Server host — program name + cmdline for LISTEN (not this laptop). */
function busyListenWhoisCommands(ports: number[]): string {
  const uniq = [...new Set(ports.filter((p) => p > 0))].sort((a, b) => a - b)
  if (uniq.length === 0) return ''
  const list = uniq.join(' ')
  const ssOr = uniq.map((p) => `sport = :${p}`).join(' or ')
  const lsofArgs = uniq.map((p) => `-iTCP:${p}`).join(' ')
  return [
    `for p in ${list}; do echo "== :$p =="; sudo ss -lptnH "sport = :$p"; done`,
    `ps -o pid,user,cmd -p $(sudo ss -lptnH '${ssOr}' | grep -o 'pid=[0-9]*' | cut -d= -f2 | sort -u | paste -sd, -)`,
    `sudo lsof -nP -sTCP:LISTEN ${lsofArgs}`,
  ].join('\n')
}

type PlannedPorts = {
  public_port: number
  agent_port: number
  node_http_port: number
  p2p_port: number
  /** Stellar captive-core HTTP_QUERY (tip catalog). */
  captive_core_http_query_port?: number
  admin_port?: number
  drifted?: boolean
  source: 'agent' | 'workload'
}

type Props = {
  env: string
  workload: Workload | null
  status: StatusPayload | null
  /** False until first status.json poll finishes — do not flash Ports/Install. */
  statusReady?: boolean
  /** Host server display name / id */
  serverLabel?: string | null
  serverURL?: string | null
  /** Registry server (agent version / update). */
  server?: RegistryNode | null
  onRefresh: () => Promise<void> | void
  /** Called after ports are saved / re-provisioned from agent plan */
  onWorkloadUpdated?: () => Promise<void> | void
}

const STEPS_TRON: { id: WizardStepId; label: string; blurb: string }[] = [
  { id: 'ports', label: 'Check ports', blurb: 'Tip catalog ports' },
  { id: 'install', label: 'Install', blurb: 'Start automated setup' },
  { id: 'snapshot', label: 'Snapshot', blurb: 'Download chain data' },
  { id: 'start', label: 'Start', blurb: 'Launch node' },
  { id: 'done', label: 'Finish', blurb: 'Node online' },
]

const STEPS_NO_SNAP: { id: WizardStepId; label: string; blurb: string }[] = [
  { id: 'ports', label: 'Check ports', blurb: 'Tip catalog ports' },
  { id: 'install', label: 'Install', blurb: 'Start automated setup' },
  { id: 'start', label: 'Start / sync', blurb: 'Launch node and catch up' },
  { id: 'done', label: 'Healthy', blurb: 'Node ready' },
]

function wizardSteps(allowSnapshot: boolean) {
  return allowSnapshot ? STEPS_TRON : STEPS_NO_SNAP
}

function stepIndex(id: WizardStepId, allowSnapshot: boolean): number {
  return wizardSteps(allowSnapshot).findIndex((s) => s.id === id)
}

/** RPC up — used for start progress; ops handoff uses nodeReadyForOps. */
function isOnline(status: StatusPayload | null): boolean {
  if (!status) return false
  if (status.connect?.ready) return true
  if (supportsIbdStep(status) && (status.sync?.ibd || status.rpc?.initialblockdownload)) {
    return false
  }
  return !!(status.rpc?.reachable || status.rpc?.http_ok)
}

function stillSyncingInWizard(status: StatusPayload | null): boolean {
  if (!status) return false
  if (nodeReadyForOps(status)) return false
  if (status.sync?.ibd || status.rpc?.initialblockdownload) return true
  const phase = (status.ui_phase || status.lifecycle?.phase || '').toLowerCase()
  const ns = (status.node_status || '').toLowerCase()
  const cur = (
    status.lifecycle?.current_step_id ||
    status.lifecycle?.current ||
    ''
  ).toLowerCase()
  return phase === 'run' || ns === 'syncing' || cur === 'run' || cur === 'ibd'
}

function snapReady(status: StatusPayload | null): boolean {
  return !!status?.snapshot?.ready
}

function snapRunning(status: StatusPayload | null): boolean {
  const snap = status?.snapshot
  if (!snap) return false
  const phase = (snap.phase || '').toLowerCase()
  return !!snap.wget_running || phase === 'download' || phase === 'extract' || phase === 'extracting'
}

/** Windows-setup style wizard: ports → install → [snapshot] → start. Agent ACK only. */
export function NodeInstallWizard({
  env,
  workload,
  status,
  statusReady = false,
  serverLabel,
  serverURL,
  server,
  onRefresh,
  onWorkloadUpdated,
}: Props) {
  // null until resolved — never flash Ports/Install before status.json
  const [uiStep, setUiStep] = useState<WizardStepId | null>(null)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [log, setLog] = useState<string[]>([])
  const [ports, setPorts] = useState<PlannedPorts | null>(null)
  const [portsLoading, setPortsLoading] = useState(false)
  const [portsError, setPortsError] = useState<string | null>(null)
  const [checkedPorts, setCheckedPorts] = useState<CheckedCatalogPort[]>([])
  const [reachNote, setReachNote] = useState<string | null>(null)
  const [portsConfirming, setPortsConfirming] = useState(false)
  /** After provision ACK: countdown on Confirm before first status poll (gives tip/leaf time to settle). */
  const [portsConfirmCountdown, setPortsConfirmCountdown] = useState(0)
  const [unsupported, setUnsupported] = useState<UnsupportedCapability | null>(null)
  const [channelLatest, setChannelLatest] = useState('')
  const [updateOpen, setUpdateOpen] = useState(false)
  const [updating, setUpdating] = useState(false)
  const [diskMounts, setDiskMounts] = useState<HostMountInfo[]>([])
  const [diskUnused, setDiskUnused] = useState<HostDiskInfo[]>([])
  const [diskInsights, setDiskInsights] = useState<HostDiskInsight[]>([])
  const [diskSummary, setDiskSummary] = useState('')
  const [diskRecommended, setDiskRecommended] = useState<MultiDiskLayoutPlan | null>(null)
  const [diskLayout, setDiskLayout] = useState<MultiDiskLayoutPlan | null>(null)
  const [diskRoles, setDiskRoles] = useState<DiskRoleDef[]>([])
  const [diskRules, setDiskRules] = useState<string[]>([])
  const [diskLoading, setDiskLoading] = useState(false)
  const [diskError, setDiskError] = useState<string | null>(null)
  const [xrplHistory, setXrplHistory] = useState<XrplHistoryMode>('weeks')
  const [installGroups, setInstallGroups] = useState<InstallOptionGroup[]>([])
  const [installOptions, setInstallOptions] = useState<Record<string, string>>({})
  const [killTarget, setKillTarget] = useState<CheckedCatalogPort | null>(null)
  const [killing, setKilling] = useState(false)
  const [installModalOpen, setInstallModalOpen] = useState(false)
  const [installOutcome, setInstallOutcome] = useState<InstallProgressOutcome | null>(null)
  const [installError, setInstallError] = useState<string | null>(null)
  const autoStarted = useRef(false)
  const nodeStartSent = useRef(false)
  const portsFetched = useRef(false)
  const disksFetched = useRef(false)
  /** Local step only after agent ACK of the click that advanced it. */
  const agentAckedStep = useRef<WizardStepId | null>(null)
  const networkId = (workload?.network || '').toLowerCase()
  const wantsDiskLayout = MULTI_DISK_NETWORKS.has(networkId)
  const wantsXrplHistory = isXrplNetwork(networkId)
  const wantsInstallOptions = installGroups.length > 0

  const agentVer = unsupported?.agentVersion || server?.agent_version || ''
  const latestVer = channelLatest || server?.latest_agent_version || ''
  const needsAgentUpdate =
    !!unsupported ||
    (!!latestVer &&
      (!!server?.agent_update_available || agentVersionOutdated(agentVer, latestVer)))

  const pub = ports?.public_port || workload?.public_port || status?.connect?.rpc_port
  const agentPort = ports?.agent_port || workload?.agent_port
  const nodeHttp = ports?.node_http_port || workload?.node_http_port
  const p2p = ports?.p2p_port || workload?.p2p_port
  const currentStep = useMemo(
    () => resolveCurrentStep(status?.lifecycle),
    [status?.lifecycle],
  )
  // Prefer shared resolver (handles 0..1 fraction vs 0..100 percent).
  const syncProgress = resolveSyncProgressPct(status)
  const progress =
    syncProgress ??
    (status?.snapshot?.pct != null ? pct(status.snapshot.pct) : null) ??
    (currentStep?.pct != null ? pct(currentStep.pct as number | string) : null)
  const syncingInWizard = stillSyncingInWizard(status)

  const allowSnap = supportsSnapshotStep(status, workload?.network)
  const steps = wizardSteps(allowSnap)
  const unitHint =
    (workload?.network || status?.lifecycle?.profile?.network || 'node').toLowerCase() +
    '-' +
    env
  const busyWhoisCmd = useMemo(
    () =>
      busyListenWhoisCommands(
        checkedPorts.filter((p) => p.bind === 'busy').map((p) => p.port),
      ),
    [checkedPorts],
  )

  const agentTarget = useMemo<AgentTarget>(
    () => ({
      node: workload?.id,
      network: workload?.network || undefined,
      env,
      server: workload?.server_id || undefined,
    }),
    [workload?.id, workload?.network, workload?.server_id, env],
  )

  const derived: WizardStepId | null = useMemo(() => {
    // Leave wizard only when Healthy / connect.ready — not merely RPC responding.
    if (nodeReadyForOps(status)) return 'done'

    const localAck =
      agentAckedStep.current && agentAckedStep.current !== 'ports'
        ? agentAckedStep.current
        : null

    // Confirm ports in flight OR just ACK'd: never regress to Ports / stepPending
    // when tip still reports needs_provision (that was the Confirm → loader flash).
    if (portsConfirming || localAck) {
      if (localAck) {
        const advancing = wizardStepFromAgentLifecycle(status, allowSnap)
        if (
          advancing &&
          advancing !== 'ports' &&
          !status?.needs_provision &&
          stepIndex(advancing, allowSnap) > stepIndex(localAck, allowSnap)
        ) {
          return advancing
        }
        return localAck
      }
      return 'ports'
    }

    // Gate: before Install, stay on Check ports (catalog from tip at Add).
    const wlStatus = (workload?.status || '').toLowerCase()
    if (
      status?.needs_provision ||
      wlStatus === 'awaiting_ports' ||
      wlStatus === 'ready_to_install'
    ) {
      return 'ports'
    }

    // Agent lifecycle is the only source of step progress — never SQLite invent.
    const fromAgent = wizardStepFromAgentLifecycle(status, allowSnap)
    if (fromAgent) {
      if (
        agentAckedStep.current &&
        running &&
        stepIndex(agentAckedStep.current, allowSnap) >= stepIndex(fromAgent, allowSnap)
      ) {
        return agentAckedStep.current
      }
      return fromAgent
    }

    if (allowSnap && status?.snapshot?.failed) return 'snapshot'
    if (running && allowSnap && (snapRunning(status) || snapReady(status))) {
      return snapReady(status) ? 'start' : 'snapshot'
    }
    if (running && !allowSnap && agentAckedStep.current === 'start') return 'start'

    if (statusReady) {
      if (uiStep) return uiStep
      return 'ports'
    }
    return uiStep
  }, [status, running, uiStep, portsConfirming, statusReady, allowSnap, workload?.status])

  const active = derived
  // Never flash the wizard-wide loader during/after Confirm ports.
  const stepPending =
    active == null && !portsConfirming && !(agentAckedStep.current && agentAckedStep.current !== 'ports')

  // Prefer agent-owned sync/IBD lines when present; keep local wizard ACK lines too.
  const displayLog = useMemo(() => {
    const agentLines = agentLogLines(status)
    if (agentLines.length === 0) return log
    const merged = [...log]
    for (const ln of agentLines.slice(-30)) {
      if (!merged.includes(ln)) merged.push(ln)
    }
    return merged.slice(-50)
  }, [log, status])

  const wizardLogScroller = useRef<HTMLDivElement | null>(null)
  const wizardLogCopiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [wizardLogCopied, setWizardLogCopied] = useState(false)
  const displayLogJoined = displayLog.join('\n')
  useLayoutEffect(() => {
    const el = wizardLogScroller.current
    if (!el || displayLog.length === 0) return
    el.scrollTop = el.scrollHeight
    requestAnimationFrame(() => {
      el.scrollTop = el.scrollHeight
    })
  }, [displayLogJoined, displayLog.length])

  useEffect(() => {
    return () => {
      if (wizardLogCopiedTimer.current) clearTimeout(wizardLogCopiedTimer.current)
    }
  }, [])

  function copyWizardLogs() {
    if (displayLog.length === 0) return

    void copyText(displayLogJoined)
      .then(() => {
        setWizardLogCopied(true)
        if (wizardLogCopiedTimer.current) clearTimeout(wizardLogCopiedTimer.current)
        wizardLogCopiedTimer.current = setTimeout(() => setWizardLogCopied(false), 1500)
        notifications.show({
          color: 'teal',
          message: 'Logs copied',
          autoClose: 2000,
        })
      })
      .catch(() => {
        notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
      })
  }

  function pushLog(line: string) {
    setLog((prev) => [...prev.slice(-40), `${new Date().toLocaleTimeString()}  ${line}`])
  }

  function startInstallWatch() {
    setInstallError(null)
    setInstallOutcome('running')
    setInstallModalOpen(true)
  }

  function markInstallOk() {
    setInstallOutcome((prev) => (prev === 'fail' ? prev : 'ok'))
    setInstallModalOpen(true)
  }

  function markInstallFail(msg: string) {
    setInstallOutcome('fail')
    setInstallError(msg)
    setInstallModalOpen(true)
  }

  async function askAgentPorts(force = false) {
    if (!workload?.server_id) {
      setPortsError('No server linked to this node — cannot ask agent for ports')
      return
    }
    // Already assigned (regtest :39293/:39393 etc.) — do not re-plan a new free pair
    // unless user clicks Re-scan (that would drift ports and break Confirm ACK).
    // Still probe plan so an outdated tip agent surfaces unsupported network/env.
    if (!force && workload.public_port && workload.agent_port) {
      setPorts({
        public_port: workload.public_port,
        agent_port: workload.agent_port,
        node_http_port: workload.node_http_port || 0,
        p2p_port: workload.p2p_port || 0,
        source: 'workload',
      })
      pushLog(
        `Using assigned ports: RPC ${workload.public_port}, agent ${workload.agent_port}, P2P ${workload.p2p_port || '—'}`,
      )
    }
    setPortsLoading(true)
    setPortsError(null)
    setUnsupported(null)
    try {
      const res = await api.workloadsPlan({
        server_id: workload.server_id,
        network: workload.network || 'tron',
        env,
      })
      const blocked = detectUnsupportedCapability(res)
      if (blocked) {
        setUnsupported({
          ...blocked,
          agentVersion: blocked.agentVersion || server?.agent_version || '',
        })
        setPorts(null)
        setPortsError(null)
        pushLog(`Agent does not support ${workload.network || '?'}/${env}: ${blocked.message}`)
        return
      }
      if (!res.ok || !res.public_port || !res.agent_port) {
        throw new Error(res.message || res.error || 'agent plan failed')
      }
      const queryPort =
        Number(res.captive_core_http_query_port) ||
        Number((res as { ports?: { captive_core_http_query_port?: number } }).ports?.captive_core_http_query_port) ||
        0
      const adminPort =
        Number(res.admin_port) ||
        Number((res as { ports?: { admin_port?: number } }).ports?.admin_port) ||
        0
      setPorts({
        public_port: res.public_port!,
        agent_port: res.agent_port!,
        node_http_port: res.node_http_port || 0,
        p2p_port: res.p2p_port || 0,
        captive_core_http_query_port: queryPort || undefined,
        admin_port: adminPort || undefined,
        drifted: !!(res as { drifted?: boolean }).drifted,
        source: 'agent',
      })
      const planGroups = parseInstallOptionGroups(res.install_options)
      if (planGroups.length) {
        setInstallGroups(planGroups)
        setInstallOptions((prev) => {
          const next = { ...prev }
          const saved = workload.install_options || {}
          for (const g of planGroups) {
            if (!next[g.id]) next[g.id] = saved[g.id] || g.default || g.choices[0]?.id || ''
          }
          return next
        })
      }
      const planned = (res as { checked_ports?: CheckedCatalogPort[] }).checked_ports
      if (Array.isArray(planned) && planned.length > 0) {
        setCheckedPorts(planned)
      }
      const driftNote = ''
      pushLog(
        force
          ? `Agent re-scanned: RPC ${res.public_port}, agent ${res.agent_port}, P2P ${res.p2p_port}${queryPort ? `, captive-query ${queryPort}` : ''}${driftNote}`
          : `Agent catalog ports: RPC ${res.public_port}, agent ${res.agent_port}, P2P ${res.p2p_port}${queryPort ? `, captive-query ${queryPort}` : ''}${driftNote}`,
      )
    } catch (e) {
      const msg = String((e as Error).message || e)
      const blocked = detectUnsupportedCapability({ message: msg, error: msg })
      if (blocked) {
        setUnsupported({
          ...blocked,
          agentVersion: blocked.agentVersion || server?.agent_version || '',
        })
        setPorts(null)
        setPortsError(null)
      } else if (workload.public_port) {
        setPorts({
          public_port: workload.public_port,
          agent_port: workload.agent_port || 0,
          node_http_port: workload.node_http_port || 0,
          p2p_port: workload.p2p_port || 0,
          source: 'workload',
        })
        setPortsError(`Agent plan failed — showing previously assigned ports. ${msg}`)
      } else {
        setPortsError(msg)
      }
    } finally {
      setPortsLoading(false)
    }
  }

  useEffect(() => {
    void api
      .agentChannel()
      .then((res) => {
        const v = (res.version || '').trim()
        if (v) setChannelLatest(v)
      })
      .catch(() => undefined)
  }, [])

  async function confirmAgentUpdate() {
    if (!workload?.server_id) return
    setUpdating(true)
    setUpdateOpen(false)
    try {
      const res = await api.agentUpdate({ force: false }, { server: workload.server_id })
      if (res.ok === false) {
        throw new Error(res.message || res.error || 'update failed')
      }
      // Tip schedules unit restart after HTTP ACK — wait before re-check ports / version.
      if (res.updated) {
        pushLog('Agent binaries installed — waiting 10s for restart…')
        for (let n = 10; n >= 1; n--) {
          pushLog(`Waiting for agent restart… ${n}s`)
          await sleep(1000)
        }
      }
      notifications.show({
        color: res.updated ? 'teal' : 'gray',
        message: res.updated
          ? `Agent updated → ${res.version || latestVer || 'CDN'}`
          : `Agent already on ${res.version || agentVer || '?'}`,
      })
      await onWorkloadUpdated?.()
      portsFetched.current = false
      setUnsupported(null)
      void askAgentPorts(true)
    } catch (e) {
      notifications.show({
        color: 'red',
        message: String((e as Error).message || e),
      })
    } finally {
      setUpdating(false)
    }
  }

  // Keep local uiStep in sync once the resolved step is known (no mount default).
  useEffect(() => {
    if (active && uiStep == null) setUiStep(active)
  }, [active, uiStep])

  // Seed Confirm ports from panel workload so the button is not stuck disabled
  // while agent plan is still loading (or plan returns a different free pair).
  // Skip when tip agent rejected network/env — Confirm must stay blocked.
  useEffect(() => {
    if (ports || unsupported) return
    if (!workload?.public_port || !workload?.agent_port) return
    setPorts({
      public_port: workload.public_port,
      agent_port: workload.agent_port,
      node_http_port: workload.node_http_port || 0,
      p2p_port: workload.p2p_port || 0,
      source: 'workload',
    })
  }, [
    ports,
    unsupported,
    workload?.public_port,
    workload?.agent_port,
    workload?.node_http_port,
    workload?.p2p_port,
  ])

  useEffect(() => {
    if (stepPending || active !== 'ports') return
    if (portsFetched.current) return
    if (!workload?.server_id) return
    portsFetched.current = true
    void askAgentPorts(false)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, stepPending, workload?.server_id, env])

  function applyPortCheck(check: Awaited<ReturnType<typeof api.workloadsCheckPorts>>) {
    if (Array.isArray(check.checked_ports)) {
      setCheckedPorts(check.checked_ports)
    }
    if (check.reach && check.reach.open_ok === false && check.reach.message) {
      setReachNote(check.reach.message)
    } else {
      setReachNote(null)
    }
    if (!check.ok || check.ports_free === false) {
      setPortsError(formatPortBusy(check))
    } else {
      setPortsError(null)
    }
  }

  async function refreshPortCheck() {
    if (!workload?.server_id) return
    const check = await api.workloadsCheckPorts({
      server_id: workload.server_id,
      network: workload.network || 'tron',
      env,
    })
    applyPortCheck(check)
  }

  async function confirmKillHolder() {
    if (!workload?.server_id || !killTarget?.port) return
    setKilling(true)
    try {
      const res = await api.workloadsPortHolderKill({
        server_id: workload.server_id,
        network: workload.network || 'tron',
        env,
        port: killTarget.port,
        pid: killTarget.pid,
        confirm: true,
      })
      if (!res.ok) {
        throw new Error(res.message || res.error || 'kill failed')
      }
      notifications.show({
        color: 'teal',
        message: res.message || `Killed ${killTarget.comm || 'process'} on :${killTarget.port}`,
      })
      setKillTarget(null)
      await refreshPortCheck()
    } catch (e) {
      notifications.show({
        color: 'red',
        message: String((e as Error).message || e),
      })
    } finally {
      setKilling(false)
    }
  }

  useEffect(() => {
    if (stepPending || active !== 'ports' || portsConfirming) return
    if (!workload?.server_id || !ports?.public_port || unsupported) return
    let cancelled = false
    void api
      .workloadsCheckPorts({
        server_id: workload.server_id,
        network: workload.network || 'tron',
        env,
      })
      .then((check) => {
        if (cancelled) return
        applyPortCheck(check)
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [
    active,
    stepPending,
    portsConfirming,
    unsupported,
    workload?.server_id,
    workload?.network,
    env,
    ports?.public_port,
  ])

  useEffect(() => {
    if (stepPending || active !== 'ports') return
    if (disksFetched.current) return
    if (!workload?.server_id) return
    disksFetched.current = true
    void loadHostDisks()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [networkId, active, stepPending, workload?.server_id, env])

  useEffect(() => {
    const groups = fallbackInstallGroups(networkId, env)
    setInstallGroups(groups)
    const saved = workload?.install_options || {}
    const next: Record<string, string> = {}
    for (const g of groups) {
      next[g.id] = saved[g.id] || g.default || g.choices[0]?.id || ''
    }
    for (const [k, v] of Object.entries(saved)) {
      if (v && !next[k]) next[k] = v
    }
    setInstallOptions(next)
  }, [networkId, env, workload?.id])

  async function loadHostDisks() {
    if (!workload?.server_id || !networkId) return
    setDiskLoading(true)
    setDiskError(null)
    try {
      const res = await api.workloadsHostDisks({
        server_id: workload.server_id,
        network: networkId,
        env,
      })
      if (!res.ok) {
        throw new Error(res.message || res.error || 'host disks failed')
      }
      setDiskMounts(res.mounts || [])
      setDiskUnused(res.unused || [])
      setDiskInsights(res.insights || [])
      setDiskSummary(res.summary || '')
      setDiskRules(res.layout_rules || [])
      setDiskRoles(res.multi_disk_roles || [])
      const rec = res.recommended || null
      setDiskRecommended(rec)
      // Prefer panel-persisted layout (retry / re-open wizard) over tip recommend.
      let saved: MultiDiskLayoutPlan | null = null
      if (workload.id) {
        try {
          const dl = await api.workloadsDiskLayout(workload.id)
          const raw = dl.disk_layout
          if (raw && (raw.roles || raw.roles_map || raw.ledger_dir || raw.state_dir || raw.strategy)) {
            const rolesArr = Array.isArray(raw.roles)
              ? raw.roles
              : Object.entries(
                  (raw.roles_map ||
                    (raw.roles && !Array.isArray(raw.roles) ? raw.roles : {})) as Record<
                    string,
                    { dir?: string; mount?: string }
                  >,
                ).map(([id, v]) => ({ id, dir: v?.dir, mount: v?.mount }))
            saved = {
              ...(raw as MultiDiskLayoutPlan),
              roles: rolesArr.length ? rolesArr : (raw as MultiDiskLayoutPlan).roles,
            }
          }
        } catch {
          /* ignore — tip recommend is fine */
        }
      }
      setDiskLayout(saved || rec)
    } catch (e) {
      setDiskError(String((e as Error).message || e))
    } finally {
      setDiskLoading(false)
    }
  }

  function diskLayoutPayload(layout: MultiDiskLayoutPlan) {
    const rolesMap =
      layout.roles_map ||
      Object.fromEntries(
        (layout.roles || [])
          .filter((r) => r.id)
          .map((r) => [r.id, { dir: r.dir, mount: r.mount }]),
      )
    return {
      ledger_dir: layout.ledger_dir,
      accounts_dir: layout.accounts_dir,
      snapshots_dir: layout.snapshots_dir,
      disk_layout: {
        strategy: layout.strategy,
        ledger_mount: layout.ledger_mount,
        accounts_mount: layout.accounts_mount,
        snapshots_mount: layout.snapshots_mount,
        ledger_dir: layout.ledger_dir,
        accounts_dir: layout.accounts_dir,
        snapshots_dir: layout.snapshots_dir,
        state_dir: layout.state_dir,
        index_dir: layout.index_dir,
        state_mount: layout.state_mount,
        index_mount: layout.index_mount,
        roles: rolesMap,
      },
    }
  }

  /** Install: tip check catalog ports → provision → lifecycle ACK (no Confirm / remap). */
  async function installWithPortCheck() {
    if (portsConfirming) return
    if (!workload?.id) {
      const msg = 'Node id missing — reload the page'
      setPortsError(msg)
      notifications.show({ color: 'red', message: msg })
      return
    }
    if (!workload.server_id || !ports?.public_port) {
      const msg = 'Catalog ports missing — re-add the node so tip can return ports'
      setPortsError(msg)
      notifications.show({ color: 'red', message: msg })
      return
    }
    setPortsConfirming(true)
    setPortsConfirmCountdown(0)
    setPortsError(null)
    let advanced = false
    try {
      pushLog('Check ports: tip catalog…')
      const check = await api.workloadsCheckPorts({
        server_id: workload.server_id,
        network: workload.network || 'tron',
        env,
      })
      if (Array.isArray(check.checked_ports)) {
        setCheckedPorts(check.checked_ports)
      }
      if (check.reach && check.reach.open_ok === false && check.reach.message) {
        setReachNote(check.reach.message)
        pushLog(`Reach: ${check.reach.message}`)
        throw new Error(check.reach.message)
      } else {
        setReachNote(null)
      }
      if (!check.ok || check.ports_free === false) {
        throw new Error(formatPortBusy(check))
      }
      setPortsError(null)
      pushLog('Check ports: free (or reclaimable by this node) — provisioning…')

      const layout = wantsDiskLayout ? diskLayout || diskRecommended : null
      if (wantsDiskLayout && layout) {
        const roleSummary = (layout.roles || [])
          .map((r) => `${r.id}=${r.dir}`)
          .join(' ')
        pushLog(`Disk layout: ${layout.strategy || '?'} ${roleSummary || layout.ledger_dir || ''}`.trim())
      }
      if (wantsInstallOptions) {
        pushLog(`Install options: ${installOptionLabel(installGroups, installOptions)}`)
      }
      const res = await api.workloadsProvision({
        server_id: workload.server_id,
        network: workload.network || 'tron',
        env,
        name: workload.name,
        public_port: ports.public_port,
        agent_port: ports.agent_port,
        node_http_port: ports.node_http_port,
        p2p_port: ports.p2p_port,
        ...(layout ? diskLayoutPayload(layout) : {}),
        ...(wantsXrplHistory
          ? { xrpl_history: installOptions.xrpl_history || xrplHistory }
          : {}),
        ...(wantsInstallOptions ? { install_options: installOptions } : {}),
      })
      if (!res.ok) {
        const blocked = detectUnsupportedCapability(res)
        if (blocked) {
          setUnsupported({
            ...blocked,
            agentVersion: blocked.agentVersion || server?.agent_version || '',
          })
          throw new Error(blocked.message)
        }
        const busy = (res as { busy_ports?: { role?: string; port?: number }[] }).busy_ports
        const busyMsg = busy?.length
          ? busy.map((b) => `${b.role}=${b.port}`).join(', ')
          : ''
        throw new Error(
          res.message || res.error || (busyMsg ? `Ports busy: ${busyMsg}` : 'provision failed'),
        )
      }
      if (res.item) {
        setPorts({
          public_port: res.item.public_port || ports.public_port,
          agent_port: res.item.agent_port || ports.agent_port,
          node_http_port: res.item.node_http_port || ports.node_http_port,
          p2p_port: res.item.p2p_port || ports.p2p_port,
          source: 'agent',
        })
      }
      pushLog('Agent ACK: provision ok — waiting 10s before status check…')
      for (let n = 10; n >= 1; n--) {
        setPortsConfirmCountdown(n)
        await sleep(1000)
      }
      setPortsConfirmCountdown(0)
      pushLog('Checking agent lifecycle status…')
      void onRefresh()

      const acked = await waitAgentLifecycleAck(agentTarget, 'ports', {
        timeoutMs: 75_000,
        acceptCurrent: ['install', 'snapshot', 'start', 'ibd', 'run'],
        onTick: (st) => {
          const d = st.lifecycle?.detail || st.lifecycle?.steps?.find((s) => s.id === 'ports')?.detail
          if (d) pushLog(`ports: ${d}`)
        },
      })
      await api.workloadsSetStatus({ id: workload.id, status: 'installing' })
      const snapBlock = snapshotBlockMessage(acked)
      if (snapBlock) {
        setError(snapBlock)
        setUiStep('snapshot')
        agentAckedStep.current = 'snapshot'
        await setWlStatus('snapshot_error')
        pushLog(`ERROR: ${snapBlock}`)
        markInstallFail(snapBlock)
        notifications.show({ color: 'red', message: snapBlock, autoClose: 8000 })
        advanced = true
        await onWorkloadUpdated?.()
        void onRefresh()
        return
      }
      const next = wizardStepFromAgentLifecycle(acked, allowSnap)
      const portsStep = acked.lifecycle?.steps?.find((s) => (s.id || '') === 'ports')
      const leaveTo: WizardStepId =
        next && next !== 'ports' && !acked.needs_provision ? next : 'install'
      agentAckedStep.current = leaveTo
      setUiStep(leaveTo)
      advanced = true
      pushLog(
        `Agent ACK: ports status=${portsStep?.status || '?'} current=${acked.lifecycle?.current || '?'}`,
      )
      await onWorkloadUpdated?.()
      void onRefresh()
      notifications.show({ color: 'teal', message: 'Ports OK — installing' })
      // After provision the leaf current is often already `start` (install done,
      // unit not up). Must still POST start — do not treat that as finished.
      if (leaveTo === 'done' && nodeReadyForOps(acked)) {
        markInstallOk()
      } else {
        void beginInstall()
      }
    } catch (e) {
      const msg = String((e as Error).message || e)
      pushLog(`ERROR: ${msg}`)
      markInstallFail(msg)
      notifications.show({ color: 'red', message: msg, autoClose: 8000 })
      if (/insufficient disk|snapshot/i.test(msg)) {
        setError(msg)
        setUiStep('snapshot')
        agentAckedStep.current = 'snapshot'
        void setWlStatus('snapshot_error')
      } else {
        setPortsError(msg)
      }
      setPortsConfirming(false)
      setPortsConfirmCountdown(0)
    } finally {
      if (advanced) {
        setPortsConfirming(false)
        setPortsConfirmCountdown(0)
      }
    }
  }

  async function setWlStatus(st: string) {
    if (!workload?.id) return
    try {
      await api.workloadsSetStatus({ id: workload.id, status: st })
    } catch {
      /* ignore */
    }
  }

  async function beginInstall() {
    setError(null)
    autoStarted.current = true
    nodeStartSent.current = false
    setRunning(true)
    try {
      if (!allowSnap) {
        // Bitcoin / no-snapshot: real provision (bitcoind + units) then start — both need agent ACK.
        pushLog('Install: provision on host agent…')
        if (!workload?.server_id) {
          throw new Error('No server linked — cannot install')
        }
        const layout = wantsDiskLayout ? diskLayout || diskRecommended : null
        const prov = await api.workloadsProvision({
          server_id: workload.server_id,
          network: workload.network || 'tron',
          env,
          name: workload.name,
          public_port: ports?.public_port || workload.public_port,
          agent_port: ports?.agent_port || workload.agent_port,
          node_http_port: ports?.node_http_port || workload.node_http_port,
          p2p_port: ports?.p2p_port || workload.p2p_port,
          ...(layout ? diskLayoutPayload(layout) : {}),
          ...(wantsXrplHistory
          ? { xrpl_history: installOptions.xrpl_history || xrplHistory }
          : {}),
          ...(wantsInstallOptions ? { install_options: installOptions } : {}),
        })
        if (!prov.ok) {
          throw new Error(prov.message || prov.error || 'provision failed')
        }
        pushLog('Agent ACK: provision ok')
        await onWorkloadUpdated?.()
        await onRefresh()

        pushLog('Install: waiting agent lifecycle install…')
        await waitAgentLifecycleAck(agentTarget, 'install', {
          timeoutMs: 90_000,
          acceptCurrent: ['start', 'ibd', 'run'],
          onTick: (st) => {
            const d =
              st.lifecycle?.detail ||
              st.lifecycle?.steps?.find((s) => s.id === 'install')?.detail
            if (d) pushLog(`install: ${d}`)
          },
        })
        pushLog('Agent ACK: install started/done')

        pushLog('Install: start node…')
        const started = await api.workloadsStart({
          workload_id: workload?.id,
          server_id: workload?.server_id,
          env,
        })
        if (!started.ok) {
          throw new Error(started.message || started.error || 'start failed')
        }
        pushLog('Agent API ACK: start ok — waiting lifecycle…')
        const afterStart = await waitAgentLifecycleAck(agentTarget, 'start', {
          timeoutMs: 90_000,
          acceptCurrent: ['ibd', 'run', 'healthy'],
          onTick: (st) => {
            const d =
              st.lifecycle?.detail ||
              st.lifecycle?.steps?.find((s) => (s.id || '') === 'start' || s.id === 'ibd')
                ?.detail
            if (d) pushLog(`start: ${d}`)
          },
        })
        await setWlStatus('starting')
        const next = wizardStepFromAgentLifecycle(afterStart, allowSnap) || 'start'
        agentAckedStep.current = next === 'install' ? 'start' : next
        setUiStep(agentAckedStep.current)
        notifications.show({ color: 'teal', message: 'Install started (agent ACK)' })
        markInstallOk()
      } else if (!snapReady(status) && !snapRunning(status)) {
        pushLog('Requesting snapshot download…')
        await api.snapshotStart(agentTarget)
        const afterStart = await api.status(agentTarget, { live: true })
        const blocked = snapshotBlockMessage(afterStart)
        if (blocked) {
          throw new Error(blocked)
        }
        pushLog('Agent API ACK: snapshot start — waiting lifecycle…')
        const acked = await waitAgentLifecycleAck(agentTarget, 'snapshot', {
          timeoutMs: 60_000,
          acceptCurrent: snapReady(status) ? ['start', 'run'] : [],
        })
        agentAckedStep.current = wizardStepFromAgentLifecycle(acked, allowSnap) || 'snapshot'
        setUiStep(agentAckedStep.current)
        await setWlStatus('snapshot_running')
      } else if (snapReady(status)) {
        pushLog('Snapshot already ready — starting node…')
        agentAckedStep.current = 'start'
        setUiStep('start')
      } else {
        pushLog('Agent reports snapshot already in progress')
        agentAckedStep.current = 'snapshot'
        setUiStep('snapshot')
        await setWlStatus('snapshot_running')
      }
      await onRefresh()
      await onWorkloadUpdated?.()
    } catch (e) {
      const msg = String((e as Error).message || e)
      if (/already/i.test(msg)) {
        pushLog(msg)
        // Still require lifecycle ACK before leaving Install.
        try {
          const acked = await waitAgentLifecycleAck(
            agentTarget,
            allowSnap ? 'snapshot' : 'install',
            {
              timeoutMs: 45_000,
              acceptCurrent: allowSnap
                ? snapReady(status)
                  ? ['start', 'run']
                  : []
                : ['start', 'ibd', 'run'],
            },
          )
          const next = wizardStepFromAgentLifecycle(acked, allowSnap)
          if (next && next !== 'install' && next !== 'ports') {
            agentAckedStep.current = next
            setUiStep(next)
            if (!allowSnap) await setWlStatus('starting')
            await onRefresh()
            await onWorkloadUpdated?.()
            return
          }
        } catch (ackErr) {
          const ackMsg = String((ackErr as Error).message || ackErr)
          setError(ackMsg)
          setRunning(false)
          setUiStep('install')
          agentAckedStep.current = 'install'
          pushLog(`ERROR: ${ackMsg}`)
          markInstallFail(ackMsg)
          notifications.show({ color: 'red', message: ackMsg })
          return
        }
      }
      // No agent ACK → stay on Install, show short error from agent.
      setError(msg)
      setRunning(false)
      setUiStep('install')
      agentAckedStep.current = 'install'
      pushLog(`ERROR: ${msg}`)
      markInstallFail(msg)
      notifications.show({ color: 'red', message: msg })
      await onWorkloadUpdated?.()
    }
  }

  // Surface agent-reported snapshot failure into wizard + node status.
  useEffect(() => {
    if (!allowSnap) return
    if (isOnline(status) || snapReady(status)) return
    const msg = snapshotBlockMessage(status)
    if (!msg) return
    setRunning(false)
    setUiStep('snapshot')
    setError(msg)
    pushLog(`Snapshot failed: ${msg}`)
    markInstallFail(msg)
    void setWlStatus('snapshot_error').then(() => onWorkloadUpdated?.())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    allowSnap,
    status?.snapshot?.failed,
    status?.snapshot?.error,
    status?.snapshot?.detail,
    status?.snapshot?.phase,
    status?.lifecycle?.node_status,
    status?.lifecycle?.phase,
    status?.lifecycle?.detail,
  ])

  // Auto-advance: snapshot ready → start node; node online → done.
  useEffect(() => {
    if (!running && !autoStarted.current) return

    let cancelled = false

    async function tick() {
      if (cancelled) return

      if (allowSnap && status?.snapshot?.failed) {
        return
      }

      if (nodeReadyForOps(status)) {
        setRunning(false)
        setUiStep('done')
        await setWlStatus('online')
        pushLog('Node online')
        markInstallOk()
        await onWorkloadUpdated?.()
        return
      }

      if (!allowSnap) {
        setUiStep('start')
        return
      }

      if (snapReady(status) && !nodeStartSent.current) {
        nodeStartSent.current = true
        setRunning(true)
        pushLog(`Snapshot ready — starting ${unitHint}…`)
        try {
          await api.workloadsStart({
            workload_id: workload?.id,
            server_id: workload?.server_id,
            env,
          })
          const acked = await waitAgentLifecycleAck(agentTarget, 'start', {
            timeoutMs: 90_000,
            acceptCurrent: ['run', 'healthy'],
          })
          const next = wizardStepFromAgentLifecycle(acked, allowSnap) || 'start'
          agentAckedStep.current = next
          setUiStep(next)
          await setWlStatus('starting')
          pushLog('Agent ACK: start started/done')
          markInstallOk()
          await onRefresh()
          await onWorkloadUpdated?.()
        } catch (e) {
          const msg = String((e as Error).message || e)
          setError(msg)
          pushLog(`Start failed: ${msg}`)
          markInstallFail(msg)
          setRunning(false)
          setUiStep('install')
          agentAckedStep.current = 'install'
          await onWorkloadUpdated?.()
        }
        return
      }

      if (snapRunning(status) || running) {
        const fromAgent = wizardStepFromAgentLifecycle(status, allowSnap)
        if (fromAgent === 'snapshot' || fromAgent === 'start') setUiStep(fromAgent)
      }
    }

    void tick()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [allowSnap, status?.snapshot?.ready, status?.snapshot?.wget_running, status?.snapshot?.failed, status?.rpc?.reachable, status?.rpc?.http_ok, running])

  // Resume only from agent lifecycle — never from SQLite ports_confirmed / starting alone.
  useEffect(() => {
    if (autoStarted.current || running || portsConfirming) return
    // Never auto-skip Confirm ports on a fresh / awaiting_ports row.
    if (status?.needs_provision || (workload?.status || '').toLowerCase() === 'awaiting_ports') {
      return
    }
    const fromAgent = wizardStepFromAgentLifecycle(status, allowSnap)
    if (!fromAgent || fromAgent === 'ports' || fromAgent === 'install' || fromAgent === 'done') {
      return
    }
    if (allowSnap && status?.snapshot?.failed) {
      setUiStep('snapshot')
      setError(
        status.snapshot.error ||
          status.snapshot.detail ||
          currentStep?.detail ||
          'Snapshot download failed',
      )
      return
    }
    if (fromAgent === 'start' || fromAgent === 'snapshot') {
      autoStarted.current = true
      setRunning(true)
      agentAckedStep.current = fromAgent
      setUiStep(fromAgent)
      startInstallWatch()
      pushLog(`Resumed from agent lifecycle (${fromAgent})`)
    }
  }, [status, running, portsConfirming, allowSnap, currentStep?.detail, workload?.status])

  const idx = active ? stepIndex(active, allowSnap) : -1

  return (
    <>
    {installOutcome === 'running' && !installModalOpen ? (
      <Alert
        color="yellow"
        variant="light"
        mb="sm"
        title="Install in progress"
      >
        <Group justify="space-between" wrap="wrap">
          <Text size="sm">Host logs keep updating in the background.</Text>
          <Button size="xs" variant="light" color="yellow" onClick={() => setInstallModalOpen(true)}>
            Show logs
          </Button>
        </Group>
      </Alert>
    ) : null}
    <Box
      style={{
        display: 'grid',
        gridTemplateColumns: 'minmax(200px, 260px) 1fr',
        minHeight: 420,
        border: '1px solid var(--mantine-color-dark-4)',
        borderRadius: 12,
        overflow: 'hidden',
        background: 'linear-gradient(160deg, #121a22 0%, #0c1218 55%, #152028 100%)',
      }}
    >
      <Box
        p="lg"
        style={{
          borderRight: '1px solid var(--mantine-color-dark-4)',
          background: 'rgba(0,0,0,0.25)',
        }}
      >
        <Text size="xs" c="dimmed" tt="uppercase" fw={700} mb="md">
          Node setup
        </Text>
        {stepPending ? (
          <Skeleton height={22} mb="md" radius="sm" />
        ) : (
          currentStep &&
          active !== 'done' && (
            <Badge color="yellow" variant="light" mb="md" fullWidth>
              {currentStep.headline}
              {currentStep.pct != null ? ` · ${String(currentStep.pct)}%` : ''}
            </Badge>
          )
        )}
        {(serverLabel || serverURL) && (
          <Stack gap={2} mb="md">
            <Group gap={6} wrap="nowrap">
              <IconServer size={14} style={{ opacity: 0.8 }} />
              <Text size="sm" fw={600} lineClamp={1} title={serverLabel || undefined}>
                {serverLabel
                  ? /^\d{1,3}(\.\d{1,3}){3}$/.test(serverLabel.trim())
                    ? maskHostname(serverLabel.trim())
                    : serverLabel
                  : 'Server'}
              </Text>
            </Group>
            {serverURL ? (
              <CopyMaskedUrl url={serverURL} compact copyMessage="Agent URL copied" />
            ) : null}
          </Stack>
        )}
        <Stack gap="sm">
          {steps.map((s, i) => {
            if (stepPending) {
              return (
                <Group key={s.id} gap="sm" wrap="nowrap" opacity={0.45}>
                  <Skeleton height={28} width={28} radius="xl" />
                  <div style={{ flex: 1 }}>
                    <Skeleton height={14} width={72} mb={6} />
                    <Skeleton height={10} width={110} />
                  </div>
                </Group>
              )
            }
            const done = i < idx || active === 'done'
            const current = s.id === active
            return (
              <Group key={s.id} gap="sm" wrap="nowrap" opacity={i > idx && active !== 'done' ? 0.45 : 1}>
                <ThemeIcon
                  size={28}
                  radius="xl"
                  color={done ? 'teal' : current ? 'cyan' : 'dark'}
                  variant={current ? 'filled' : 'light'}
                >
                  {done ? <IconCheck size={14} /> : <Text size="xs">{i + 1}</Text>}
                </ThemeIcon>
                <div>
                  <Text size="sm" fw={current ? 700 : 500}>
                    {s.label}
                  </Text>
                  <Text size="xs" c="dimmed">
                    {s.blurb}
                  </Text>
                </div>
              </Group>
            )
          })}
        </Stack>
      </Box>

      <Box p="xl">
        <Stack gap="md">
          {stepPending && (
            <Stack align="center" gap="sm" py="xl">
              <Loader color="teal" />
              <Text c="dimmed" size="sm">
                Loading node status…
              </Text>
            </Stack>
          )}

          {!stepPending && active === 'ports' && (
            <>
              <Title order={3}>Check ports</Title>
              <Text c="dimmed" size="sm">
                Fixed catalog ports for this network/env (no remap). Bind is local LISTEN on the
                host. Reach is this panel dialing the node public IP (not a probe from the node).
                Filtered = cloud security group or host firewall. The agent does not open ports.
                Clients use Go RPC (public); Agent API is a separate control port.
              </Text>

              {portsLoading && !portsConfirming && (
                <Alert color="gray" title="Loading tip catalog…">
                  Fetching fixed ports for Go RPC, Node Agent API, upstream and P2P…
                </Alert>
              )}

              {portsConfirming && (
                <Alert
                  color="cyan"
                  title={
                    portsConfirmCountdown > 0
                      ? `Waiting ${portsConfirmCountdown}s…`
                      : 'Checking ports / installing…'
                  }
                >
                  {portsConfirmCountdown > 0
                    ? 'Ports provisioned — short settle delay, then agent lifecycle status is checked.'
                    : 'Waiting for host agent ACK — Confirm stays locked until lifecycle leaves Ports or an error is returned.'}
                </Alert>
              )}

              {unsupported && (
                <Alert
                  color="orange"
                  icon={<IconAlertTriangle size={16} />}
                  title={`${(workload?.network || 'network').toUpperCase()} · ${env} not supported`}
                >
                  <Stack gap="sm">
                    <Text size="sm">
                      {unsupported.message ||
                        `${workload?.network || 'network'}/${env} is not supported by this agent. Update the host agent to the latest version.`}
                    </Text>
                    <Group justify="space-between" align="center" wrap="nowrap" gap="sm">
                      <Group gap={6} align="center" wrap="nowrap">
                        <Text size="xs" c="dimmed" tt="uppercase" style={{ letterSpacing: 0.4 }}>
                          Agent
                        </Text>
                        <Text
                          size="sm"
                          fw={600}
                          className="mono"
                          c={needsAgentUpdate ? 'orange' : 'dimmed'}
                          title={
                            latestVer
                              ? `Installed ${agentVer || '?'} — CDN latest ${latestVer}`
                              : 'Installed agent version'
                          }
                        >
                          {agentVer || '—'}
                        </Text>
                        {latestVer ? (
                          <Badge color="orange" variant="light" size="sm">
                            → {latestVer}
                          </Badge>
                        ) : null}
                      </Group>
                      <Tooltip
                        label={
                          latestVer
                            ? `Update agent ${agentVer || '?'} → ${latestVer}`
                            : 'Update host agent from CDN'
                        }
                      >
                        <span>
                          <ActionIcon
                            color="orange"
                            variant="light"
                            size="lg"
                            loading={updating}
                            disabled={updating || !workload?.server_id}
                            aria-label="Update agent"
                            onClick={() => setUpdateOpen(true)}
                          >
                            <IconDownload size={16} />
                          </ActionIcon>
                        </span>
                      </Tooltip>
                    </Group>
                  </Stack>
                </Alert>
              )}

              {portsError && !unsupported && (
                <Alert color="red" title="Ports busy">
                  <Stack gap={8}>
                    <Text size="sm">{portsError}</Text>
                    {busyWhoisCmd ? (
                      <>
                        <Text size="sm">
                          On the Server host (SSH) — program name + cmdline:
                        </Text>
                        <Code
                          block
                          className="mono"
                          style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}
                        >
                          {busyWhoisCmd}
                        </Code>
                        <Button
                          size="xs"
                          variant="light"
                          leftSection={<IconCopy size={14} />}
                          onClick={() => {
                            void copyText(busyWhoisCmd)
                              .then(() => {
                                notifications.show({
                                  color: 'blue',
                                  message: 'Whois commands copied',
                                  autoClose: 2000,
                                })
                              })
                              .catch(() => {
                                notifications.show({
                                  color: 'red',
                                  message: 'Copy failed',
                                  autoClose: 2000,
                                })
                              })
                          }}
                        >
                          Copy commands
                        </Button>
                      </>
                    ) : null}
                  </Stack>
                </Alert>
              )}

              {!unsupported && (
                <Alert
                  color="cyan"
                  icon={<IconLockOpen size={16} />}
                  title={
                    ports?.source === 'agent'
                      ? 'Catalog ports from host agent (fixed)'
                      : 'Ports for this node'
                  }
                >
                  <Stack gap={6} mt={4}>
                    {checkedPorts.length > 0
                      ? checkedPorts.map((p) => (
                          <PortLine
                            key={`${p.role}-${p.port}`}
                            label={p.label || p.role}
                            port={p.port}
                            external={!!p.external}
                            bind={p.bind}
                            holder={p.holder}
                            pid={p.pid}
                            comm={p.comm}
                            cmdline={p.cmdline}
                            unit={p.unit}
                            killable={p.killable}
                            killBlocked={p.kill_blocked}
                            reach={p.reach}
                            onKill={
                              p.bind === 'busy' ? () => setKillTarget(p) : undefined
                            }
                          />
                        ))
                      : (
                          <>
                            <PortLine label="Go RPC (proxy)" port={pub} external />
                            <PortLine label="Node Agent API" port={agentPort} external />
                            <PortLine label="Upstream HTTP / RPC" port={nodeHttp} external={false} />
                            <PortLine label="P2P" port={p2p} external />
                            {!!ports?.captive_core_http_query_port && (
                              <PortLine
                                label="Captive Core HTTP_QUERY"
                                port={ports.captive_core_http_query_port}
                                external={false}
                              />
                            )}
                            {!!ports?.admin_port && (
                              <PortLine label="RPC admin" port={ports.admin_port} external={false} />
                            )}
                          </>
                        )}
                  </Stack>
                  {reachNote && (
                    <Alert color="red" mt="sm" title="Panel cannot reach these ports">
                      {reachNote} Install is blocked until public / agent / P2P are reachable from
                      outside (cloud SG + host firewall). XRPL peers need inbound TCP 51235.
                    </Alert>
                  )}
                </Alert>
              )}

              {wantsInstallOptions && !unsupported && (
                <InstallOptionsPicker
                  groups={installGroups}
                  value={installOptions}
                  onChange={setInstallOptions}
                  disabled={portsConfirming}
                />
              )}

              {wantsXrplHistory && !wantsInstallOptions && !unsupported && (
                <Stack gap="xs">
                  <Text size="sm" fw={600}>
                    History to install
                  </Text>
                  <Text size="xs" c="dimmed">
                    Choose the ledger window or Full history. This is what xrpld will keep after
                    Install.
                  </Text>
                  <XrplHistoryPicker
                    value={xrplHistory}
                    onChange={setXrplHistory}
                    disabled={portsConfirming}
                  />
                </Stack>
              )}

              {!unsupported && (
                <HostDiskInsights
                  network={networkId}
                  loading={diskLoading}
                  error={diskError}
                  mounts={diskMounts}
                  unused={diskUnused}
                  insights={diskInsights}
                  summary={diskSummary}
                />
              )}

              {wantsDiskLayout && !unsupported && (
                <DiskLayoutPanel
                  network={networkId}
                  env={env}
                  loading={diskLoading}
                  error={diskError}
                  mounts={diskMounts}
                  roles={diskRoles}
                  recommended={diskRecommended}
                  layout={diskLayout}
                  rules={diskRules}
                  onChange={setDiskLayout}
                  onUseRecommended={() => {
                    if (diskRecommended) setDiskLayout(diskRecommended)
                    else void loadHostDisks()
                  }}
                />
              )}

              <Group justify="space-between" mt="sm">
                <Button
                  variant="default"
                  loading={portsLoading || diskLoading}
                  disabled={!workload?.server_id || portsConfirming}
                  onClick={() => {
                    portsFetched.current = true
                    setPortsError(null)
                    void askAgentPorts(true)
                    void loadHostDisks()
                  }}
                >
                  {unsupported ? 'Check agent again' : 'Refresh catalog'}
                </Button>
                <Button
                  color="teal"
                  size="md"
                  rightSection={
                    portsConfirmCountdown > 0 ? undefined : <IconRocket size={16} />
                  }
                  loading={portsConfirming && portsConfirmCountdown === 0}
                  disabled={
                    !!unsupported ||
                    portsConfirming ||
                    portsLoading ||
                    !ports?.public_port ||
                    !ports?.agent_port ||
                    !!reachNote
                  }
                  onClick={() => {
                    setPortsError(null)
                    startInstallWatch()
                    void installWithPortCheck()
                  }}
                >
                  {portsConfirmCountdown > 0
                    ? `Check status in ${portsConfirmCountdown}s`
                    : portsConfirming
                      ? 'Installing…'
                      : wantsInstallOptions
                        ? `Install · ${installOptionLabel(installGroups, installOptions)}`
                        : wantsXrplHistory
                          ? `Install · ${xrplHistoryInstallLabel(xrplHistory)}`
                          : 'Install'}
                </Button>
              </Group>

              <Modal
                opened={updateOpen}
                onClose={() => (!updating ? setUpdateOpen(false) : undefined)}
                title="Update host agent?"
                centered
              >
                <Stack gap="md">
                  <Alert color="yellow" icon={<IconAlertTriangle size={16} />}>
                    Downloads <strong>api-agent + system-agent</strong> from CDN and restarts their
                    systemd units. Brief disconnect possible. Then re-check ports for{' '}
                    <Code>
                      {workload?.network || '?'}/{env}
                    </Code>
                    .
                  </Alert>
                  <Text size="sm">
                    <Text span fw={700}>
                      {serverLabel || server?.name || server?.id || 'Server'}
                    </Text>
                    : <Code className="mono">{agentVer || '?'}</Code>
                    {' → '}
                    <Code className="mono">{latestVer || 'CDN latest'}</Code>
                  </Text>
                  <Group justify="flex-end">
                    <Button variant="default" onClick={() => setUpdateOpen(false)}>
                      Cancel
                    </Button>
                    <Button
                      color="teal"
                      leftSection={<IconDownload size={14} />}
                      loading={updating}
                      onClick={() => void confirmAgentUpdate()}
                    >
                      Confirm update + restart
                    </Button>
                  </Group>
                </Stack>
              </Modal>

              <Modal
                opened={!!killTarget}
                onClose={() => (!killing ? setKillTarget(null) : undefined)}
                title="Kill process on this port?"
                centered
              >
                <Stack gap="md">
                  <Alert color="red" icon={<IconAlertTriangle size={16} />}>
                    Sends SIGTERM, then SIGKILL, on the Server host. Tip agent, sshd, and this
                    node&apos;s own units are refused.
                  </Alert>
                  <Text size="sm">
                    <Code className="mono">:{killTarget?.port}</Code>
                    {killTarget?.label ? ` · ${killTarget.label}` : ''}
                  </Text>
                  <Text size="sm">
                    <Text span fw={700}>
                      {killTarget?.comm || 'unknown'}
                    </Text>
                    {killTarget?.pid ? (
                      <>
                        {' '}
                        pid <Code className="mono">{killTarget.pid}</Code>
                      </>
                    ) : null}
                    {killTarget?.unit ? (
                      <>
                        {' '}
                        · <Code className="mono">{killTarget.unit}</Code>
                      </>
                    ) : null}
                  </Text>
                  {killTarget?.cmdline ? (
                    <Code block className="mono" style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
                      {killTarget.cmdline}
                    </Code>
                  ) : null}
                  {killTarget && killTarget.killable === false ? (
                    <Alert color="orange">
                      {killTarget.kill_blocked || 'Agent will not kill this process'}
                    </Alert>
                  ) : null}
                  <Group justify="flex-end">
                    <Button variant="default" disabled={killing} onClick={() => setKillTarget(null)}>
                      Cancel
                    </Button>
                    <Button
                      color="red"
                      leftSection={<IconX size={14} />}
                      loading={killing}
                      disabled={killTarget?.killable !== true}
                      onClick={() => void confirmKillHolder()}
                    >
                      Kill process
                    </Button>
                  </Group>
                </Stack>
              </Modal>
            </>
          )}

          {active === 'install' && (
            <>
              <Title order={3}>Ready to install</Title>
              <Text c="dimmed" size="sm">
                One click starts the full sequence. No more manual steps until the node is online.
              </Text>
              <Stack gap={6}>
                {allowSnap ? (
                  <>
                    <Text size="sm">1. Download snapshot (backup / chain data)</Text>
                    <Text size="sm">
                      2. When ready — start <Code>{unitHint}</Code>
                    </Text>
                    <Text size="sm">3. Wait until RPC responds</Text>
                  </>
                ) : (
                  <>
                    <Text size="sm">
                      1. Start <Code>{unitHint}</Code>
                    </Text>
                    <Text size="sm">2. Wait for RPC</Text>
                  </>
                )}
                {(currentStep?.detail || status?.lifecycle?.detail) && (
                  <Text size="xs" c="dimmed">
                    {currentStep?.detail || status?.lifecycle?.detail}
                  </Text>
                )}
              </Stack>
              {error && (
                <Alert color="red" title="Could not start">
                  {error}
                </Alert>
              )}
              <Group justify="space-between" mt="sm">
                <Button variant="default" onClick={() => setUiStep('ports')}>
                  Back
                </Button>
                <Button
                  color="teal"
                  size="md"
                  leftSection={<IconPlayerPlay size={16} />}
                  loading={running}
                  onClick={() => {
                    startInstallWatch()
                    void beginInstall()
                  }}
                >
                  Install
                </Button>
              </Group>
            </>
          )}

          {allowSnap &&
            (active === 'snapshot' || (active === 'start' && !snapReady(status) && running)) && (
            <>
              <Group justify="space-between">
                <Title order={3}>
                  {currentStep?.headline || 'Installing snapshot'}
                </Title>
                <Badge color="yellow" variant="light">
                  automatic
                </Badge>
              </Group>
              <Text c="dimmed" size="sm">
                {currentStep?.detail ||
                  status?.lifecycle?.detail ||
                  'Downloading and preparing chain data. Wizard continues when the marker is ready.'}
              </Text>
              <Progress value={progress ?? (snapRunning(status) ? 8 : 2)} animated striped size="lg" />
              <Group gap="lg">
                <Text size="sm">
                  Progress:{' '}
                  <Text span fw={700}>
                    {String(currentStep?.pct ?? status?.snapshot?.pct ?? '…')}
                  </Text>
                </Text>
                <Text size="sm" c="dimmed">
                  ETA {status?.snapshot?.eta || '—'} ·{' '}
                  {status?.lifecycle?.label || status?.snapshot?.phase || 'starting'}
                </Text>
              </Group>
              {(currentStep?.detail || status?.snapshot?.detail) && (
                <Text size="xs" c="dimmed">
                  {currentStep?.detail || status?.snapshot?.detail}
                </Text>
              )}
              {(error || snapshotBlockMessage(status)) && (
                <Alert color="red" title="Snapshot error">
                  {error ||
                    snapshotBlockMessage(status) ||
                    status?.snapshot?.error ||
                    status?.snapshot?.detail ||
                    'Snapshot download failed'}
                  <Button
                    mt="sm"
                    size="xs"
                    variant="light"
                    onClick={() => {
                      setError(null)
                      startInstallWatch()
                      void beginInstall()
                    }}
                  >
                    Retry
                  </Button>
                </Alert>
              )}
            </>
          )}

          {active === 'start' &&
            (allowSnap ? snapReady(status) : running || !nodeReadyForOps(status)) && (
            <>
              <Group justify="space-between">
                <Title order={3}>
                  {stillSyncingInWizard(status)
                    ? currentStep?.headline || status?.lifecycle?.label || 'Syncing'
                    : currentStep?.headline || status?.lifecycle?.label || 'Starting node'}
                </Title>
                <Badge color="cyan" variant="light">
                  automatic
                </Badge>
              </Group>
              <Text c="dimmed" size="sm">
                {currentStep?.detail || status?.lifecycle?.detail || (
                  stillSyncingInWizard(status) ? (
                    <>
                      <Code>{unitHint}</Code> is running — waiting until the node is healthy /
                      caught up…
                    </>
                  ) : (
                    <>
                      Starting <Code>{unitHint}</Code> and waiting for RPC…
                    </>
                  )
                )}
              </Text>
              <Stack gap={6}>
                <Group justify="space-between" align="flex-end">
                  <Text size="sm" c="dimmed" tt="uppercase" fw={700}>
                    Sync progress
                  </Text>
                  <Text fw={800} size="xl" style={{ letterSpacing: '-0.03em' }} ta="right">
                    {typeof status?.sync?.slots_behind === 'number' &&
                    status.sync.slots_behind > 0 &&
                    syncingInWizard ? (
                      <>
                        {status.sync.slots_behind.toLocaleString()}
                        <Text span size="sm" c="dimmed" fw={600} ml={6}>
                          behind
                        </Text>
                        {syncProgress != null ? (
                          <Text span size="sm" c="dimmed" fw={600} ml={8}>
                            · {formatSyncPct(syncProgress)} lag closed
                          </Text>
                        ) : null}
                      </>
                    ) : syncProgress != null ? (
                      formatSyncPct(syncProgress)
                    ) : syncingInWizard ? (
                      '…'
                    ) : (
                      '—'
                    )}
                  </Text>
                </Group>
                {typeof status?.sync?.slot === 'number' &&
                (typeof status?.sync?.cluster_slot === 'number' ||
                  typeof status?.sync?.headers === 'number') ? (
                  <Text size="xs" c="dimmed" className="mono" ta="right">
                    node {Number(status.sync.slot).toLocaleString()} · tip{' '}
                    {Number(
                      status.sync.cluster_slot ?? status.sync.headers ?? 0,
                    ).toLocaleString()}
                  </Text>
                ) : null}
                <Progress
                  value={
                    syncProgress != null
                      ? syncProgress
                      : nodeReadyForOps(status)
                        ? 100
                        : 0
                  }
                  animated={syncingInWizard && (syncProgress == null || syncProgress < 100)}
                  striped={syncingInWizard && (syncProgress == null || syncProgress < 100)}
                  size="xl"
                  radius="xl"
                  color={
                    syncProgress != null && syncProgress >= 100 && !syncingInWizard
                      ? 'teal'
                      : 'cyan'
                  }
                  style={{ minHeight: 14 }}
                />
              </Stack>
              {error && (
                <Alert color="red" title="Start failed">
                  <Stack gap="sm">
                    <Text size="sm" style={{ overflowWrap: 'anywhere' }}>
                      {error}
                    </Text>
                    <Button
                      size="xs"
                      variant="light"
                      color="red"
                      style={{ alignSelf: 'flex-start' }}
                      onClick={() => {
                        nodeStartSent.current = false
                        setError(null)
                        startInstallWatch()
                        void beginInstall()
                      }}
                    >
                      Retry start
                    </Button>
                  </Stack>
                </Alert>
              )}
            </>
          )}

          {/* done → parent hides wizard via needsInstallWizard; no Continue gate */}
          {active === 'done' && (
            <Group gap="sm">
              <Loader size="sm" color="teal" />
              <Text c="dimmed" size="sm">
                Opening ops…
              </Text>
            </Group>
          )}

          {displayLog.length > 0 && active !== 'ports' && (
            <Stack gap={6}>
              <Group justify="flex-end" gap={4}>
                <Tooltip label={wizardLogCopied ? 'Copied' : 'Copy logs'}>
                  <ActionIcon
                    size="sm"
                    variant="light"
                    color={wizardLogCopied ? 'teal' : 'gray'}
                    aria-label="Copy logs"
                    onClick={copyWizardLogs}
                  >
                    {wizardLogCopied ? <IconCheck size={14} /> : <IconCopy size={14} />}
                  </ActionIcon>
                </Tooltip>
              </Group>
              <Box
                ref={wizardLogScroller}
                style={{ maxHeight: 280, overflow: 'auto' }}
              >
                <Code
                  block
                  className="mono"
                  style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}
                >
                  {displayLogJoined}
                </Code>
              </Box>
            </Stack>
          )}

          {active !== 'done' && active !== 'ports' && (
            <Group gap={6} c="dimmed">
              <IconDownload size={14} />
              <Text size="xs">Installer waits for agent lifecycle ACK — keep this page open or reopen anytime.</Text>
            </Group>
          )}
        </Stack>
      </Box>
    </Box>
    <InstallProgressModal
      opened={installModalOpen}
      onClose={() => setInstallModalOpen(false)}
      serverId={workload?.server_id}
      serverName={serverLabel || server?.name}
      network={workload?.network || networkId}
      env={env}
      outcome={installOutcome || 'running'}
      error={installError || error}
      wizardLines={log}
      onRefreshStatus={onRefresh}
    />
    </>
  )
}

function PortLine({
  label,
  port,
  external,
  bind,
  holder,
  pid,
  comm,
  cmdline,
  unit,
  killable,
  killBlocked,
  reach,
  onKill,
}: {
  label: string
  port?: number
  external: boolean
  bind?: string
  holder?: string
  pid?: string
  comm?: string
  cmdline?: string
  unit?: string
  killable?: boolean
  killBlocked?: string
  reach?: string
  onKill?: () => void
}) {
  const bindBusy = bind === 'busy'
  const who = [comm, pid ? `pid ${pid}` : '', unit, holder].filter(Boolean).join(' · ')
  const reachColor =
    reach === 'filtered' ? 'red' : reach === 'reachable' ? 'teal' : 'gray'
  return (
    <Stack gap={4}>
    <Group justify="space-between" wrap="wrap">
      <Text size="sm">{label}</Text>
      <Group gap={6}>
        <Badge color={external ? 'cyan' : 'gray'} variant="light" size="sm">
          {external ? 'external' : 'internal'}
        </Badge>
        {bind ? (
          <Badge color={bindBusy ? 'red' : 'teal'} variant="light" size="sm">
            {bindBusy ? `busy${who ? ` · ${who}` : ''}` : 'free'}
          </Badge>
        ) : null}
        {bindBusy && onKill ? (
          <Tooltip
            label={
              killable === true
                ? 'Kill process'
                : killBlocked || 'Update agent to inspect/kill this process'
            }
          >
            <span>
              <Button
                size="compact-xs"
                color="red"
                variant="light"
                disabled={killable !== true}
                leftSection={<IconX size={12} />}
                onClick={onKill}
              >
                Kill
              </Button>
            </span>
          </Tooltip>
        ) : null}
        {external && reach && reach !== 'n/a' ? (
          <Badge color={reachColor} variant="light" size="sm">
            {reach}
          </Badge>
        ) : null}
        <Code className="mono">{port != null && port > 0 ? String(port) : '—'}</Code>
      </Group>
    </Group>
    {bindBusy && cmdline ? (
      <Text size="xs" c="dimmed" className="mono" lineClamp={2}>
        {cmdline}
      </Text>
    ) : null}
    </Stack>
  )
}

/**
 * Show install wizard (left steps) until first Healthy / connect.ready.
 * RPC-up alone is not enough (XRPL/BTC/L2 sync) — but restart / client update
 * of an already-provisioned node must NOT reopen NODE SETUP.
 */
export function needsInstallWizard(status: StatusPayload | null, workload: Workload | null): boolean {
  if (nodeReadyForOps(status)) return false
  // Invariant: Sync badge Synced ⇔ no NODE SETUP (panel heal + UI gate).
  if (statusHonestlySynced(status)) return false

  const wlStatus = (workload?.status || '').toLowerCase()
  const agentPort = Number(workload?.agent_port || 0)
  // Stale SQLite awaiting_ports/ready_to_install while leaf already healthy/synced
  // (panel heal may lag one poll) — do not reopen NODE SETUP.
  if (
    agentPort > 0 &&
    (wlStatus === 'awaiting_ports' || wlStatus === 'ready_to_install') &&
    status &&
    (status.connect?.ready === true ||
      status.lifecycle?.complete === true ||
      statusHonestlySynced(status))
  ) {
    return false
  }
  // Before Install — always NODE SETUP (even if tip unreachable / agent_error cache).
  const unprovisioned =
    !workload ||
    agentPort <= 0 ||
    wlStatus === 'awaiting_ports' ||
    wlStatus === 'ports_confirmed' ||
    wlStatus === 'ready_to_install' ||
    (wlStatus === 'agent_error' && agentPort <= 0)
  if (unprovisioned) return true

  if (!status) return true

  // Transient ops overlays — keep Network UI (not setup rail).
  const nr = (status.node_restart?.phase || '').toLowerCase()
  if (nr === 'restarting' || nr === 'starting' || nr === 'stopping' || nr === 'stopped') return false
  const cu = (status.client_update?.phase || '').toLowerCase()
  if (cu === 'updating' || cu === 'starting') return false
  const ui = (status.ui_phase || '').toLowerCase()
  if (ui === 'restarting' || ui === 'updating' || ui === 'stopping' || ui === 'stopped') return false

  // Lifecycle completed once — stay in ops only when actually ready (not false Healthy).
  if (status.lifecycle?.complete && nodeReadyForOps(status)) return false
  if (status.lifecycle?.complete && status.sync?.ok === false) {
    // complete=true but sync not ok (Sui genesis / tip-dead) — keep wizard + Run step.
    return true
  }
  if (status.lifecycle?.complete) return false

  if (status.needs_provision) return true

  const phase = (status.ui_phase || status.lifecycle?.phase || '').toLowerCase()
  const cur = (
    status.lifecycle?.current_step_id ||
    status.lifecycle?.current ||
    ''
  ).toLowerCase()
  const ns = (status.node_status || status.lifecycle?.node_status || '').toLowerCase()

  // Tip/leaf down mid-setup — keep wizard (not bare "Agent unreachable" ops shell).
  const unreachable =
    status.error === 'agent_unreachable' ||
    status.agent_reachable === false ||
    status.health === 'agent_unreachable'
  if (
    unreachable &&
    (phase === 'ports' ||
      phase === 'setup' ||
      phase === 'install' ||
      phase === 'start' ||
      phase === 'error' ||
      ns === 'awaiting_ports' ||
      ns === 'agent_error' ||
      !phase)
  ) {
    return true
  }

  // Early provision steps only.
  if (
    phase === 'ports' ||
    phase === 'install' ||
    phase === 'snapshot' ||
    phase === 'setup' ||
    phase === 'start' ||
    cur === 'ports' ||
    cur === 'install' ||
    cur === 'snapshot' ||
    cur === 'start' ||
    ns === 'awaiting_ports' ||
    ns === 'installing' ||
    ns === 'ready_to_start' ||
    ns === 'starting' ||
    ns === 'agent_error'
  ) {
    return true
  }
  // First catch-up before Healthy — keep wizard + sync %.
  if (
    phase === 'run' ||
    cur === 'run' ||
    cur === 'ibd' ||
    ns === 'syncing' ||
    phase === 'syncing'
  ) {
    return true
  }

  return false
}
