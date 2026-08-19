import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Card,
  Group,
  NavLink,
  ScrollArea,
  SegmentedControl,
  SimpleGrid,
  Stack,
  Text,
  ThemeIcon,
  Loader,
  Center,
  Modal,
  Tooltip,
} from '@mantine/core'
import {
  IconPlus,
  IconTopologyStar3,
  IconArrowRight,
  IconAlertTriangle,
  IconTrash,
  IconPlayerPlay,
  IconPlayerStop,
  IconRefresh,
  IconFileText,
  IconList,
  IconServer,
} from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  api,
  type Workload,
  type RegistryNode,
  type RPCProxyStats,
  type NodeNetStats,
  type ClientUpdateInfo,
} from '../api'
import { fmtMbps } from '../lib/format'
import { maskHostname } from '../lib/maskHost'
import type { StatusPayload } from '../types'
import { AppChrome } from '../components/AppChrome'
import { AddNodeModal } from '../components/AddNodeModal'
import { AgentLogsModal } from '../components/AgentLogsPanel'
import { ServerLogsModal } from '../components/ServerLogsModal'
import { LifecycleStepper } from '../components/LifecycleStepper'
import { NetworkIcon } from '../components/NetworkIcon'
import { NodeLifecycleDates } from '../components/NodeLifecycleDates'
import {
  RemoveConfirmInput,
  removeConfirmPhrase,
  removePhraseMatches,
} from '../components/RemoveConfirmInput'
import {
  RemoveNodeModePicker,
  removeModeToRequest,
  removeSubmitLabel,
  type RemoveNodeMode,
} from '../components/RemoveNodeModePicker'
import {
  deriveNodeLifecycle,
  splitStepHeadline,
  isForeignTronDiskError,
  clientUpdateAllowed,
  clientUpdateClickable,
  nodeRestartAllowed,
  nodeStartAllowed,
  nodeStopAllowed,
  type NodeLifecycle,
} from '../lib/nodeLifecycle'
import { ClientUpdateModal } from '../components/ClientUpdateModal'
import { formatClientVersion } from '../lib/format'
import { isNoSnapshotNetwork } from '../lib/network'
import { NETWORK_OPTIONS, networkLabel } from '../lib/networksCatalog'
import { navigate } from '../lib/router'

/** ~2 nav/card rows — keep target off the sticky edges when jumping. */
const NODES_SCROLL_PAD = 96
const NONE_SERVER_KEY = '_none'

type NodesGroupBy = 'network' | 'server'

function displayServerLabel(raw: string | undefined): string {
  const s = String(raw || '').trim()
  if (!s) return 'No server'
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(s)) return maskHostname(s)
  return s
}

function findScrollParent(el: HTMLElement | null): HTMLElement | null {
  let p = el?.parentElement ?? null
  while (p && p !== document.body) {
    const style = getComputedStyle(p)
    const oy = style.overflowY
    if ((oy === 'auto' || oy === 'scroll' || oy === 'overlay') && p.scrollHeight > p.clientHeight + 1) {
      return p
    }
    p = p.parentElement
  }
  return (document.querySelector('.panel-main') as HTMLElement | null) ?? null
}

function scrollWithPad(el: HTMLElement, pad = NODES_SCROLL_PAD, container?: HTMLElement | null) {
  const scroller = container ?? findScrollParent(el)
  if (!scroller) {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
    return
  }
  const elRect = el.getBoundingClientRect()
  const cRect = scroller.getBoundingClientRect()
  const next = scroller.scrollTop + (elRect.top - cRect.top) - pad
  const max = Math.max(0, scroller.scrollHeight - scroller.clientHeight)
  scroller.scrollTo({ top: Math.min(max, Math.max(0, next)), behavior: 'smooth' })
}

function lifecycleFromDB(w: Workload): NodeLifecycle {
  const wl = (w.status || '').toLowerCase()
  const lifePhase = (w.lifecycle_phase || '').toLowerCase()
  const lifeLabel = (w.lifecycle_label || '').toLowerCase()
  if (wl === 'removing' || lifePhase === 'removing' || lifeLabel === 'removing') {
    return {
      phase: 'removing',
      label: 'removing',
      detail: w.lifecycle_detail || 'Stopping on host — row drops after agent ACK',
      color: 'orange',
      busy: true,
      height: w.height ?? null,
    }
  }
  if (wl === 'remove_error') {
    return {
      phase: 'error',
      label: 'remove error',
      detail: w.status_error || w.lifecycle_detail || 'Tip did not ACK remove — retry Remove',
      color: 'red',
      busy: false,
      height: w.height ?? null,
    }
  }
  const noSnap = isNoSnapshotNetwork(w.network) // temporary until workloads carry supported_steps
  // Stale SQLite network_mismatch / "got tron" on bitcoin — Setup, never Wrong agent.
  const staleMismatch =
    wl === 'network_mismatch' ||
    (w.status_error || '').toLowerCase() === 'network_mismatch' ||
    /wrong agent|got tron|agent expected/i.test(
      `${w.lifecycle_label || ''} ${w.lifecycle_detail || ''} ${w.status_error || ''}`,
    )
  if (noSnap && staleMismatch) {
    return {
      phase: 'installing',
      label: w.lifecycle_label || 'Setup',
      detail: w.lifecycle_detail || 'Install to check ports on the host',
      color: 'yellow',
      busy: false,
      height: w.height ?? null,
    }
  }
  const errBlob = `${w.status_error || ''} ${w.lifecycle_detail || ''} ${w.lifecycle_label || ''}`
  if (isForeignTronDiskError(errBlob, w.network)) {
    return {
      phase: (w.lifecycle_phase as NodeLifecycle['phase']) || 'syncing',
      label: w.lifecycle_label || 'syncing',
      detail: '',
      color: 'cyan',
      busy: true,
      height: w.height ?? null,
    }
  }
  // Transient unreachable with cached lifecycle — keep phase (syncing/working),
  // do not regress to red error / STEP 1 Install.
  const cachedPhase = (w.lifecycle_phase || '').toLowerCase()
  const hasCachedLife =
    cachedPhase === 'syncing' ||
    cachedPhase === 'working' ||
    cachedPhase === 'starting' ||
    cachedPhase === 'installing'
  if (wl === 'agent_error' && hasCachedLife) {
    const phase = cachedPhase as NodeLifecycle['phase']
    return {
      phase,
      label: w.lifecycle_label || phase,
      detail: w.status_error
        ? `Agent unreachable · last: ${w.lifecycle_detail || w.lifecycle_label || phase}`
        : w.lifecycle_detail || '',
      color: phase === 'working' ? 'gray' : phase === 'syncing' ? 'cyan' : 'yellow',
      busy: phase !== 'working',
      progress: w.snapshot_progress ?? undefined,
      height: w.height ?? null,
    }
  }
  if (
    (!noSnap && wl === 'snapshot_error') ||
    wl === 'start_error' ||
    wl === 'agent_error' ||
    wl === 'network_mismatch'
  ) {
    return {
      phase: 'error',
      label:
        wl === 'network_mismatch'
          ? 'Wrong agent'
          : wl === 'snapshot_error'
            ? 'snapshot error'
            : wl === 'start_error'
              ? 'start error'
              : 'agent error',
      detail: w.status_error || w.lifecycle_detail || w.lifecycle_label || wl,
      color: 'red',
      busy: false,
      progress: w.snapshot_progress ?? undefined,
      height: w.height ?? null,
    }
  }
  // Stale snapshot_* status on no-snapshot profiles → treat as starting, not snapshot UI.
  if (noSnap && (wl === 'snapshot_error' || wl === 'snapshot_running' || wl === 'needs_snapshot')) {
    return {
      phase: 'starting',
      label: w.lifecycle_label || 'starting',
      detail: w.lifecycle_detail || 'Starting node',
      color: 'yellow',
      busy: true,
      height: w.height ?? null,
    }
  }
  if (w.lifecycle_phase) {
    let phase = w.lifecycle_phase as NodeLifecycle['phase']
    const label = w.lifecycle_label || w.lifecycle_phase || ''
    const detail = w.lifecycle_detail || w.status_error || ''
    // Stale installing/starting only — never promote syncing (Full sync / IBD) to working
    // just because label/detail contain "Running"/"healthy" (e.g. "Step 4: Running",
    // "Synced · RPC healthy"). Collector + agent phase are source of truth.
    const shortHealthy =
      /^(healthy|running|working)$/i.test(label.trim()) && (w.height ?? 0) > 0
    if (shortHealthy && (phase === 'installing' || phase === 'starting')) {
      phase = 'working'
    }
    return {
      phase,
      label,
      detail:
        phase === 'error'
          ? w.status_error || detail || 'Error'
          : detail,
      color:
        phase === 'working'
          ? 'teal'
          : phase === 'error'
            ? 'red'
            : phase === 'installing' ||
                phase === 'updating' ||
                phase === 'restarting' ||
                phase === 'stopping'
              ? 'yellow'
              : phase === 'syncing'
                ? 'cyan'
                : 'gray',
      busy:
        phase === 'working'
          ? false
          : !!w.lifecycle_busy ||
            phase === 'updating' ||
            phase === 'restarting' ||
            phase === 'stopping',
      // Collector stores lifecycle.pct (sync) in snapshot_progress for all networks.
      progress: w.snapshot_progress ?? undefined,
      height: w.height ?? null,
    }
  }
  return deriveNodeLifecycle(null, w.status, w.network)
}

type NodeCard = {
  id: string
  name?: string
  env: string
  network: string
  serverId?: string
  serverHint?: string
  publicPort?: number
  agentPort?: number
  p2pPort?: number
  agentUrl?: string
  status?: string
  statusError?: string
  agentReachable?: boolean | null
  clientVersion?: string
  clientLatest?: string
  clientUpdateAvailable?: boolean
  rpcProxy?: RPCProxyStats | null
  nodeNet?: NodeNetStats | null
  createdAt?: string
  installStartedAt?: string
  syncedAt?: string
  updatedAt?: string
  installed: boolean
  lifecycle: NodeLifecycle
}

/** Not-ready / in-progress first; healthy last. */
const READINESS_RANK: Record<string, number> = {
  removing: 0,
  setup: 1,
  installing: 2,
  updating: 3,
  restarting: 4,
  stopping: 4,
  stopped: 7,
  starting: 5,
  syncing: 6,
  error: 7,
  unknown: 8,
  working: 9,
}

/** mainnet first, then known test / pre-prod envs, then alpha fallback. */
const ENV_RANK: Record<string, number> = {
  mainnet: 0,
  sepolia: 10,
  nile: 11,
  shasta: 12,
  testnet: 13,
  testnet4: 14,
  signet: 15,
  hoodi: 16,
  puppynet: 17,
  westend: 18,
  preprod: 19,
  preview: 20,
  devnet: 21,
  localnet: 22,
  regtest: 23,
}

function readinessRank(phase: string): number {
  return READINESS_RANK[phase] ?? 5
}

function networkRank(network: string): number {
  const id = (network || '').toLowerCase()
  const idx = NETWORK_OPTIONS.findIndex((n) => n.value === id)

  return idx >= 0 ? idx : 1000
}

function envRank(env: string): number {
  const e = (env || '').toLowerCase()
  if (e in ENV_RANK) return ENV_RANK[e]
  if (/(?:test|dev|nile|sepolia|signet|regtest|puppynet|westend|preprod|preview|hoodi|shasta)/i.test(e)) {
    return 50
  }

  return 100
}

function compareNodeCards(a: NodeCard, b: NodeCard): number {
  const byReady = readinessRank(a.lifecycle.phase) - readinessRank(b.lifecycle.phase)
  if (byReady !== 0) return byReady

  const byNetRank = networkRank(a.network) - networkRank(b.network)
  if (byNetRank !== 0) return byNetRank

  const byNet = a.network.localeCompare(b.network)
  if (byNet !== 0) return byNet

  const byEnvRank = envRank(a.env) - envRank(b.env)
  if (byEnvRank !== 0) return byEnvRank

  const byEnv = a.env.localeCompare(b.env)
  if (byEnv !== 0) return byEnv

  const aName = (a.name || '').localeCompare(b.name || '')
  if (aName !== 0) return aName

  return a.id.localeCompare(b.id)
}

/** All-networks grid: keep the same network together, A–Z by display label. */
function compareCardsByNetworkLabel(a: NodeCard, b: NodeCard): number {
  const byLabel = networkLabel(a.network).localeCompare(networkLabel(b.network), undefined, {
    sensitivity: 'base',
  })
  if (byLabel !== 0) return byLabel

  return compareNodeCards(a, b)
}

/** Comma-separated: ?network=doge&env=mainnet */
function parseListParam(sp: URLSearchParams, key: string): string[] {
  const raw = sp.get(key)
  if (!raw) return []

  return [...new Set(raw.split(',').map((s) => s.trim()).filter(Boolean))]
}

function readNodesQueryFilters(): { network: string[]; env: string[] } {
  const sp = new URLSearchParams(window.location.search)

  return {
    network: parseListParam(sp, 'network').map((n) => n.toLowerCase()),
    env: parseListParam(sp, 'env').map((e) => e.toLowerCase()),
  }
}

function readNodesGroupBy(): NodesGroupBy {
  const sp = new URLSearchParams(window.location.search)
  const view = (sp.get('view') || '').toLowerCase()
  if (view === 'servers' || view === 'server') return 'server'
  // Legacy top-bar ?server=<uuid> → Servers rail + jump.
  if (parseListParam(sp, 'server').length > 0) return 'server'
  return 'network'
}

function readLegacyServerJump(): string | null {
  const sp = new URLSearchParams(window.location.search)
  return parseListParam(sp, 'server')[0] || null
}

function readRailSelection(groupBy: NodesGroupBy): string | null {
  const hash = (window.location.hash || '').replace(/^#/, '')
  const prefix = groupBy === 'network' ? 'net-' : 'srv-'
  if (hash.startsWith(prefix)) {
    const key = hash.slice(prefix.length)
    return key || null
  }
  if (groupBy === 'server') return readLegacyServerJump()
  return null
}

function syncNodesQuery(network: string[], env: string[], groupBy: NodesGroupBy, hash = window.location.hash) {
  const sp = new URLSearchParams(window.location.search)

  if (network.length > 0) {
    sp.set('network', network.join(','))
  } else {
    sp.delete('network')
  }

  if (env.length > 0) {
    sp.set('env', env.join(','))
  } else {
    sp.delete('env')
  }

  sp.delete('server')
  if (groupBy === 'server') {
    sp.set('view', 'servers')
  } else {
    sp.delete('view')
  }

  const q = sp.toString()
  const next = `${window.location.pathname}${q ? `?${q}` : ''}${hash}`
  const cur = `${window.location.pathname}${window.location.search}${window.location.hash}`

  if (next !== cur) {
    window.history.replaceState({}, '', next)
  }
}

export function NodesPage() {
  const [cards, setCards] = useState<NodeCard[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [networkFilter, setNetworkFilter] = useState<string[]>(() => readNodesQueryFilters().network)
  const [envFilter, setEnvFilter] = useState<string[]>(() => readNodesQueryFilters().env)
  const [groupBy, setGroupBy] = useState<NodesGroupBy>(() => readNodesGroupBy())
  const [activeNavKey, setActiveNavKey] = useState<string | null>(() =>
    readRailSelection(readNodesGroupBy()),
  )
  const [knownServers, setKnownServers] = useState<{ value: string; label: string }[]>([])
  const [addOpen, setAddOpen] = useState(false)
  const [removeTarget, setRemoveTarget] = useState<NodeCard | null>(null)
  const [removeMode, setRemoveMode] = useState<RemoveNodeMode>('wipe')
  const [removing, setRemoving] = useState(false)
  const [removeTyped, setRemoveTyped] = useState('')
  const [refreshing, setRefreshing] = useState(false)
  const removePhrase = removeTarget
    ? removeConfirmPhrase(removeTarget.network, removeTarget.env)
    : ''
  const removeConfirmed = !!removeTarget && removePhraseMatches(removeTyped, removePhrase)

  useEffect(() => {
    if (removeTarget) {
      setRemoveTyped('')
      setRemoveMode('wipe')
    }
  }, [removeTarget])

  useEffect(() => {
    const onPop = () => {
      const f = readNodesQueryFilters()
      const gb = readNodesGroupBy()
      setNetworkFilter(f.network)
      setEnvFilter(f.env)
      setGroupBy(gb)
      setActiveNavKey(readRailSelection(gb))
    }

    window.addEventListener('popstate', onPop)

    return () => window.removeEventListener('popstate', onPop)
  }, [])

  useEffect(() => {
    const legacy = readLegacyServerJump()
    if (!legacy) return
    const f = readNodesQueryFilters()
    setGroupBy('server')
    setActiveNavKey(legacy)
    syncNodesQuery(f.network, f.env, 'server', `#srv-${legacy}`)
  }, [])

  const load = useCallback(async (opts?: { silent?: boolean; refresh?: boolean }) => {
    if (opts?.refresh) setRefreshing(true)
    else if (!opts?.silent) setLoading(true)

    try {
      if (opts?.refresh) {
        // Kick collector force enqueue, then briefly wait for poll → SQLite before re-read.
        await api.collectorTick().catch(() => null)
        await new Promise((r) => window.setTimeout(r, 3500))
      }

      // Workloads + servers from SQLite (collector). ❌ no tip dial on list.
      const [workloads, registry] = await Promise.all([
        api.workloadsList().catch(() => null),
        api.registryList().catch(() => null),
      ])

      const servers = new Map<string, RegistryNode>()
      for (const s of registry?.items || []) {
        servers.set(s.id, s)
      }

      // Lifecycle comes from SQLite via panel-collector — no per-card agent fan-out.
      const items: Workload[] = workloads?.items || []
      const serverLabels = new Map<string, string>()
      for (const s of registry?.items || []) {
        serverLabels.set(s.id, s.name || s.id)
      }

      const next: NodeCard[] = items.map((w) => {
        const srv = servers.get(w.server_id)
        const hint = srv?.name || w.server_id
        if (w.server_id) {
          serverLabels.set(w.server_id, hint)
        }
        return {
          id: w.id,
          name: w.name,
          env: w.env,
          network: w.network || 'tron',
          serverId: w.server_id,
          serverHint: hint,
          publicPort: w.public_port,
          agentPort: w.agent_port,
          p2pPort: w.p2p_port,
          agentUrl: w.agent_url,
          status: w.status || 'awaiting_ports',
          statusError: w.status_error || '',
          agentReachable: w.agent_reachable ?? null,
          clientVersion: w.client_version || '',
          clientLatest: w.client_latest || '',
          clientUpdateAvailable: !!w.client_update_available,
          rpcProxy: w.rpc_proxy || null,
          nodeNet: w.node_net || null,
          createdAt: w.created_at || '',
          installStartedAt: w.install_started_at || '',
          syncedAt: w.synced_at || '',
          updatedAt: w.updated_at || w.status_at || '',
          installed: true,
          lifecycle: lifecycleFromDB(w),
        }
      })

      next.sort(compareNodeCards)
      setCards(next)
      setKnownServers(
        [...serverLabels.entries()]
          .map(([value, label]) => ({ value, label }))
          .sort((a, b) => a.label.localeCompare(b.label)),
      )
      setError(null)
    } catch (e) {
      setError(String((e as Error).message || e))
    } finally {
      if (opts?.refresh) setRefreshing(false)
      else if (!opts?.silent) setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
    const t = window.setInterval(() => void load({ silent: true }), 8_000)
    return () => window.clearInterval(t)
  }, [load])

  const visibleCards = useMemo(() => {
    let list = cards
    if (networkFilter.length > 0) {
      const wantNet = new Set(networkFilter.map((n) => n.toLowerCase()))
      list = list.filter((c) => wantNet.has((c.network || '').toLowerCase()))
    }
    if (envFilter.length > 0) {
      const wantEnv = new Set(envFilter.map((e) => e.toLowerCase()))
      list = list.filter((c) => wantEnv.has((c.env || '').toLowerCase()))
    }
    if (groupBy === 'network' && activeNavKey) {
      const want = activeNavKey.toLowerCase()
      list = list.filter((c) => (c.network || '').toLowerCase() === want)
    }
    if (groupBy === 'server' && activeNavKey) {
      list = list.filter((c) => (c.serverId || NONE_SERVER_KEY) === activeNavKey)
    }
    return list
  }, [cards, networkFilter, envFilter, groupBy, activeNavKey])

  /** Group /nodes grid by network (catalog order, then A–Z extras). */
  const networkGroups = useMemo(() => {
    const order = new Map<string, number>()
    NETWORK_OPTIONS.forEach((n, i) => order.set(n.value, i))
    const buckets = new Map<string, NodeCard[]>()
    for (const c of visibleCards) {
      const net = (c.network || 'unknown').toLowerCase()
      const list = buckets.get(net) || []
      list.push(c)
      buckets.set(net, list)
    }
    return [...buckets.entries()]
      .sort((a, b) => {
        const ra = order.get(a[0]) ?? 1000
        const rb = order.get(b[0]) ?? 1000
        if (ra !== rb) return ra - rb
        return a[0].localeCompare(b[0])
      })
      .map(([network, items]) => ({
        key: network,
        label: networkLabel(network),
        sub: network,
        items,
      }))
  }, [visibleCards])

  /** Group /nodes grid by server (A–Z name; unassigned last). */
  const serverGroups = useMemo(() => {
    const labels = new Map<string, string>()
    for (const s of knownServers) {
      labels.set(s.value, s.label)
    }
    const buckets = new Map<string, { label: string; items: NodeCard[] }>()
    for (const c of visibleCards) {
      const key = c.serverId || NONE_SERVER_KEY
      const raw = c.serverHint || labels.get(key) || (key === NONE_SERVER_KEY ? 'No server' : key)
      const entry = buckets.get(key) || { label: displayServerLabel(raw), items: [] }
      if (c.serverHint) entry.label = displayServerLabel(c.serverHint)
      entry.items.push(c)
      buckets.set(key, entry)
    }
    return [...buckets.entries()]
      .sort((a, b) => {
        if (a[0] === NONE_SERVER_KEY) return 1
        if (b[0] === NONE_SERVER_KEY) return -1
        return a[1].label.localeCompare(b[1].label)
      })
      .map(([key, { label, items }]) => ({
        key,
        label,
        sub: key === NONE_SERVER_KEY ? 'unassigned' : '',
        items,
      }))
  }, [visibleCards, knownServers])

  const listGroups = groupBy === 'network' ? networkGroups : serverGroups

  /** All networks: one grid, no section heads, A–Z by network label. */
  const cardsByNetworkLabel = useMemo(
    () => [...visibleCards].sort(compareCardsByNetworkLabel),
    [visibleCards],
  )

  const networkCardAnchors = useMemo(() => {
    const seen = new Set<string>()
    const byCardId = new Map<string, string>()
    for (const c of cardsByNetworkLabel) {
      const net = (c.network || 'unknown').toLowerCase()
      if (seen.has(net)) continue
      seen.add(net)
      byCardId.set(c.id, `net-${net}`)
    }
    return byCardId
  }, [cardsByNetworkLabel])

  const flatNetworks = groupBy === 'network'

  /** In-page Networks rail — counts from all loaded cards (not only filtered). */
  const networkNav = useMemo(() => {
    const order = new Map<string, number>()
    NETWORK_OPTIONS.forEach((n, i) => order.set(n.value, i))
    const counts = new Map<string, number>()
    for (const c of cards) {
      const net = (c.network || 'unknown').toLowerCase()
      counts.set(net, (counts.get(net) || 0) + 1)
    }
    return [...counts.entries()]
      .sort((a, b) => {
        const ra = order.get(a[0]) ?? 1000
        const rb = order.get(b[0]) ?? 1000
        if (ra !== rb) return ra - rb
        return a[0].localeCompare(b[0])
      })
      .map(([network, count]) => ({
        key: network,
        label: networkLabel(network),
        sub: network,
        count,
      }))
  }, [cards])

  const serverNav = useMemo(() => {
    const counts = new Map<string, { label: string; count: number }>()
    for (const c of cards) {
      const key = c.serverId || NONE_SERVER_KEY
      const raw = c.serverHint || (key === NONE_SERVER_KEY ? 'No server' : key)
      const cur = counts.get(key) || { label: displayServerLabel(raw), count: 0 }
      cur.count += 1
      if (c.serverHint) cur.label = displayServerLabel(c.serverHint)
      counts.set(key, cur)
    }
    return [...counts.entries()]
      .sort((a, b) => {
        if (a[0] === NONE_SERVER_KEY) return 1
        if (b[0] === NONE_SERVER_KEY) return -1
        return a[1].label.localeCompare(b[1].label)
      })
      .map(([key, v]) => ({
        key,
        label: v.label,
        sub: key === NONE_SERVER_KEY ? 'unassigned' : '',
        count: v.count,
      }))
  }, [cards])

  const railNav = groupBy === 'network' ? networkNav : serverNav

  const netNavViewportRef = useRef<HTMLDivElement>(null)

  const sectionDomId = useCallback(
    (key: string) => (groupBy === 'network' ? `net-${key}` : `srv-${key}`),
    [groupBy],
  )

  const keepNavItemVisible = useCallback(
    (key: string | null) => {
      if (!key) {
        netNavViewportRef.current?.scrollTo({ top: 0, behavior: 'smooth' })
        return
      }
      const attr = groupBy === 'network' ? 'data-net-nav' : 'data-srv-nav'
      const navItem = document.querySelector(
        `[${attr}="${CSS.escape(key)}"]`,
      ) as HTMLElement | null
      if (navItem) {
        scrollWithPad(navItem, Math.round(NODES_SCROLL_PAD * 0.55), netNavViewportRef.current)
      }
    },
    [groupBy],
  )

  const scrollToListTop = useCallback(() => {
    const listTop =
      (document.getElementById('nodes-list-top') as HTMLElement | null) ??
      (document.querySelector('.nodes-network-section') as HTMLElement | null)
    if (listTop) scrollWithPad(listTop, NODES_SCROLL_PAD)
  }, [])

  function jumpToGroup(key: string | null) {
    setActiveNavKey(key)
    const nets = networkFilter.length > 0 ? [] : networkFilter
    if (networkFilter.length > 0) setNetworkFilter([])
    const hash = key ? `#${groupBy === 'network' ? 'net' : 'srv'}-${key}` : ''
    syncNodesQuery(nets, envFilter, groupBy, hash)
    keepNavItemVisible(key)
    window.requestAnimationFrame(() => scrollToListTop())
  }

  function applyGroupBy(next: NodesGroupBy) {
    if (next === groupBy) return
    setGroupBy(next)
    setActiveNavKey(null)
    syncNodesQuery(networkFilter, envFilter, next, '')
    keepNavItemVisible(null)
    window.requestAnimationFrame(() => scrollToListTop())
  }

  function clearFilters() {
    setNetworkFilter([])
    setEnvFilter([])
    setActiveNavKey(null)
    syncNodesQuery([], [], groupBy, '')
  }

  async function confirmRemove() {
    if (!removeTarget) return
    setRemoving(true)
    const targetId = removeTarget.id
    const targetLabel = `${removeTarget.network}/${removeTarget.env}`
    try {
      const req = removeModeToRequest(removeMode)
      const res = await api.workloadsRemove({
        id: targetId,
        ...req,
        force: false,
      })
      if (!res.ok) throw new Error(res.message || res.error || 'remove failed')
      notifications.show({
        color: 'teal',
        title: removeMode === 'panel' ? 'Removed from panel' : 'Removing…',
        message:
          removeMode === 'panel'
            ? `${targetLabel} — panel row dropped; host was not changed`
            : removeMode === 'agents'
              ? `${targetLabel} — tip stops the node and leaf agents; chain data stays`
              : `${targetLabel} — tip runs kill → units → wipe in background`,
      })
      setRemoveTarget(null)
      setRemoveMode('wipe')
      setCards((prev) =>
        removeMode === 'panel'
          ? prev.filter((c) => c.id !== targetId)
          : prev.map((c) =>
              c.id === targetId
                ? {
                    ...c,
                    status: 'removing',
                    lifecycle: {
                      phase: 'removing',
                      label: 'removing',
                      detail: 'Tip removing — row drops after ACK',
                      color: 'orange',
                      busy: true,
                      height: c.lifecycle.height ?? null,
                    },
                  }
                : c,
            ),
      )
      await load({ silent: true })
    } catch (e) {
      const msg = String((e as Error).message || e)
      notifications.show({
        color: 'red',
        title: 'Remove failed',
        message: msg,
        autoClose: 12_000,
      })
      await load({ silent: true })
    } finally {
      setRemoving(false)
    }
  }

  if (loading && cards.length === 0) {
    return (
      <Center mih={240}>
        <Stack align="center" gap="sm">
          <Loader color="teal" />
          <Text c="dimmed">Loading nodes…</Text>
        </Stack>
      </Center>
    )
  }

  return (
    <AppChrome
      title="Nodes"
      right={
        <Group gap="xs">
          <Tooltip label="Refresh nodes">
            <ActionIcon
              size="md"
              variant="default"
              aria-label="Refresh nodes"
              loading={refreshing}
              onClick={() => void load({ refresh: true })}
            >
              <IconRefresh size={16} />
            </ActionIcon>
          </Tooltip>
          <Button
            size="xs"
            color="teal"
            leftSection={<IconPlus size={14} />}
            onClick={() => setAddOpen(true)}
          >
            Add node
          </Button>
        </Group>
      }
    >
      <div className={`nodes-page-layout${cards.length > 0 ? ' nodes-page-layout--with-nav' : ''}`}>
        {cards.length > 0 && (
          <aside className="nodes-net-nav" aria-label={groupBy === 'network' ? 'Networks on this page' : 'Servers on this page'}>
            <SegmentedControl
              className="nodes-net-nav__toggle"
              fullWidth
              size="xs"
              value={groupBy}
              onChange={(v) => applyGroupBy(v as NodesGroupBy)}
              data={[
                {
                  value: 'network',
                  label: (
                    <span className="nodes-net-nav__toggle-label">
                      <IconTopologyStar3 size={14} stroke={1.6} />
                      Networks
                    </span>
                  ),
                },
                {
                  value: 'server',
                  label: (
                    <span className="nodes-net-nav__toggle-label">
                      <IconServer size={14} stroke={1.6} />
                      Servers
                    </span>
                  ),
                },
              ]}
              aria-label="Group nodes by"
            />
            <ScrollArea
              type="scroll"
              offsetScrollbars
              scrollbarSize={6}
              className="nodes-net-nav__scroll"
              viewportRef={netNavViewportRef}
              mah="calc(100dvh - 11rem)"
            >
              <Stack gap={2}>
                <NavLink
                  label={groupBy === 'network' ? 'All networks' : 'All servers'}
                  leftSection={
                    groupBy === 'network' ? (
                      <IconList size={16} stroke={1.5} />
                    ) : (
                      <IconServer size={16} stroke={1.5} />
                    )
                  }
                  rightSection={
                    <Badge size="xs" variant="light" color="gray">
                      {cards.length}
                    </Badge>
                  }
                  active={!activeNavKey}
                  onClick={() => jumpToGroup(null)}
                />
                {railNav.map((n) => (
                  <NavLink
                    key={n.key}
                    data-net-nav={groupBy === 'network' ? n.key : undefined}
                    data-srv-nav={groupBy === 'server' ? n.key : undefined}
                    label={n.label}
                    description={n.sub || undefined}
                    leftSection={
                      groupBy === 'network' ? (
                        <NetworkIcon network={n.key} size={18} />
                      ) : (
                        <IconServer size={16} stroke={1.5} />
                      )
                    }
                    rightSection={
                      <Badge size="xs" variant="light" color="teal">
                        {n.count}
                      </Badge>
                    }
                    active={activeNavKey === n.key}
                    onClick={() => jumpToGroup(n.key)}
                  />
                ))}
              </Stack>
            </ScrollArea>
          </aside>
        )}

        <Stack gap="md" className="nodes-page-content">
          {error && (
            <Alert color="red" icon={<IconAlertTriangle size={16} />} title="Nodes error">
              {error}
            </Alert>
          )}

          {cards.length === 0 ? (
            <Card>
              <Stack gap="sm">
                <Text fw={600}>No nodes yet</Text>
                <Text size="sm" c="dimmed">
                  Register a server, then Add node: network → env → server. Tip catalog ports are
                  saved at Add; Install checks they are free and provisions the host.
                </Text>
                <Group>
                  <Button color="teal" leftSection={<IconPlus size={16} />} onClick={() => setAddOpen(true)}>
                    Add node
                  </Button>
                  <Button variant="default" onClick={() => navigate({ name: 'servers' })}>
                    Go to Servers
                  </Button>
                </Group>
              </Stack>
            </Card>
          ) : visibleCards.length === 0 ? (
            <Card>
              <Stack gap="sm">
                <Text fw={600}>No nodes match filter</Text>
                <Text size="sm" c="dimmed">
                  Clear URL filters or pick another combination.
                </Text>
                <Button variant="default" size="xs" w="fit-content" onClick={clearFilters}>
                  Show all nodes
                </Button>
              </Stack>
            </Card>
          ) : (
            <Stack gap="xl" id="nodes-list-top">
              {flatNetworks ? (
                <SimpleGrid cols={{ base: 1, sm: 2, lg: 2, xl: 3 }} spacing="md">
                  {cardsByNetworkLabel.map((c) => (
                    <NodeCardView
                      key={c.id}
                      model={c}
                      anchorId={networkCardAnchors.get(c.id)}
                      onRemove={() => {
                        setRemoveTarget(c)
                      }}
                    />
                  ))}
                </SimpleGrid>
              ) : (
                listGroups.map((g) => (
                  <Stack
                    key={`${groupBy}-${g.key}`}
                    id={sectionDomId(g.key)}
                    gap="md"
                    className="nodes-network-section"
                  >
                    <Group
                      className="nodes-network-section__head"
                      justify="space-between"
                      align="center"
                      wrap="nowrap"
                    >
                      <Group gap="sm" wrap="nowrap">
                        <ThemeIcon variant="light" color="gray" size="lg" radius="xl">
                          <IconServer size={18} stroke={1.5} />
                        </ThemeIcon>
                        <div>
                          <Text fw={700} size="sm">
                            {g.label}
                          </Text>
                          <Text size="xs" c="dimmed">
                            {g.sub ? `${g.sub} · ` : ''}
                            {g.items.length} node
                            {g.items.length === 1 ? '' : 's'}
                          </Text>
                        </div>
                      </Group>
                      <Badge variant="light" color="gray" size="sm">
                        {g.items.length}
                      </Badge>
                    </Group>
                    <SimpleGrid cols={{ base: 1, sm: 2, lg: 2, xl: 3 }} spacing="md">
                      {g.items.map((c) => (
                        <NodeCardView
                          key={c.id}
                          model={c}
                          onRemove={() => {
                            setRemoveTarget(c)
                          }}
                        />
                      ))}
                    </SimpleGrid>
                  </Stack>
                ))
              )}
            </Stack>
          )}
        </Stack>
      </div>

      <AddNodeModal opened={addOpen} onClose={() => setAddOpen(false)} onAdded={() => void load()} />

      <Modal
        opened={!!removeTarget}
        onClose={() => (!removing ? setRemoveTarget(null) : undefined)}
        title="Remove node?"
        centered
        size="md"
      >
        <Stack gap="md">
          <Text size="sm">
            Remove{' '}
            <Text span fw={700}>
              {removeTarget?.network?.toUpperCase()} · {removeTarget?.env}
            </Text>
          </Text>
          {(removeTarget?.status || '').toLowerCase() === 'removing' ||
          (removeTarget?.status || '').toLowerCase() === 'remove_error' ? (
            <Alert color="orange" icon={<IconAlertTriangle size={16} />}>
              Tip remove did not finish — pick a mode and retry. Host modes re-kick tip (leaf
              agent may already be down). Panel-only drops the row without touching the host.
            </Alert>
          ) : null}
          <RemoveNodeModePicker value={removeMode} onChange={setRemoveMode} disabled={removing} />
          {removeMode === 'wipe' && (
            <Alert color="red" icon={<IconAlertTriangle size={16} />} title="Destructive">
              Chain data will be deleted on the server.
            </Alert>
          )}
          {removeMode === 'panel' && (
            <Alert color="orange" icon={<IconAlertTriangle size={16} />}>
              The node keeps running. Re-add later may hit busy ports until you remove it on the
              host.
            </Alert>
          )}
          <RemoveConfirmInput
            phrase={removePhrase}
            value={removeTyped}
            onChange={setRemoveTyped}
            disabled={removing}
          />
          <Group justify="flex-end">
            <Button variant="default" disabled={removing} onClick={() => setRemoveTarget(null)}>
              Cancel
            </Button>
            <Button
              color="red"
              loading={removing}
              disabled={!removeConfirmed}
              leftSection={<IconTrash size={14} />}
              onClick={() => void confirmRemove()}
            >
              {removeSubmitLabel(
                removeMode,
                (removeTarget?.status || '').toLowerCase() === 'removing' ||
                  (removeTarget?.status || '').toLowerCase() === 'remove_error',
              )}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </AppChrome>
  )
}

function NodeCardView({
  model,
  onRemove,
  anchorId,
}: {
  model: NodeCard
  onRemove: () => void
  anchorId?: string
}) {
  const {
    id,
    name,
    env,
    network,
    serverId,
    serverHint,
    clientVersion,
    clientLatest,
    clientUpdateAvailable,
    rpcProxy,
    nodeNet,
    lifecycle,
  } = model
  const { phase, label, detail, color, busy, progress, height } = lifecycle
  const working = phase === 'working'
  const isRemoving =
    phase === 'removing' || (model.status || '').toLowerCase() === 'removing'
  const isRemoveError = (model.status || '').toLowerCase() === 'remove_error'
  const redundantDetail = /^(height|slot|ledger|block|checkpoint|version|seqno|snapshot)\b/i.test(
    (detail || '').trim(),
  )
  const title = name || `${network} ${env}`
  const stepParts = splitStepHeadline(isRemoving ? '' : label)
  const clientLabel = formatClientVersion(clientVersion) || '—'
  const clientLatestLabel = formatClientVersion(clientLatest)
  const clientKnown = clientLabel !== '—'
  const clientOutdated = clientKnown && !!clientUpdateAvailable
  const clientCurrent = clientKnown && !clientUpdateAvailable
  const clientColor = clientOutdated ? 'orange' : clientCurrent ? 'teal' : 'dimmed'
  const [clientBusy, setClientBusy] = useState(false)
  const [clientOpen, setClientOpen] = useState(false)
  const [clientStarted, setClientStarted] = useState(false)
  const [clientInfo, setClientInfo] = useState<ClientUpdateInfo | null>(null)
  const [restartBusy, setRestartBusy] = useState(false)
  const [restartOpen, setRestartOpen] = useState(false)
  const [stopBusy, setStopBusy] = useState(false)
  const [stopOpen, setStopOpen] = useState(false)
  const [startBusy, setStartBusy] = useState(false)
  const [startOpen, setStartOpen] = useState(false)
  const [logsOpen, setLogsOpen] = useState(false)
  const [logsStatus, setLogsStatus] = useState<StatusPayload | null>(null)
  const [logsLoading, setLogsLoading] = useState(false)

  async function openLogs(e: { stopPropagation: () => void }) {
    e.stopPropagation()
    setLogsOpen(true)
    if (serverId) return
    setLogsLoading(true)
    setLogsStatus(null)
    try {
      const st = await api.status({ node: id, network, env })
      setLogsStatus(st)
    } catch (err) {
      notifications.show({
        color: 'red',
        message: String((err as Error).message || err || 'Failed to load logs'),
      })
    } finally {
      setLogsLoading(false)
    }
  }

  useEffect(() => {
    if (!clientOpen) return
    let stop = false
    const tick = async () => {
      try {
        const res = await api.clientInfo({ node: id })
        if (!stop) setClientInfo(res.client_update || null)
      } catch {
        /* keep last */
      }
    }
    void tick()
    const t = window.setInterval(() => void tick(), 1500)
    return () => {
      stop = true
      window.clearInterval(t)
    }
  }, [clientOpen, id])

  async function confirmClientUpdate() {
    setClientBusy(true)
    setClientStarted(true)
    try {
      await api.clientCheck({ node: id })
      const res = await api.clientUpdate({ node: id })
      if (!res.ok) throw new Error(res.error || 'client update failed')
    } catch (err) {
      setClientStarted(false)
      notifications.show({ color: 'red', message: String((err as Error).message || err) })
    } finally {
      setClientBusy(false)
    }
  }

  async function confirmNodeStop() {
    setStopBusy(true)
    try {
      const res = await api.nodeStop({ node: id })
      if (!res.ok) throw new Error(res.error || 'stop failed')
      notifications.show({
        color: 'teal',
        message: res.node_restart?.detail || 'Node stop started (RPC sleep)',
      })
      setStopOpen(false)
    } catch (err) {
      notifications.show({ color: 'red', message: String((err as Error).message || err) })
    } finally {
      setStopBusy(false)
    }
  }

  async function confirmNodeRestart() {
    setRestartBusy(true)
    try {
      const res = await api.nodeRestart({ node: id })
      if (!res.ok) throw new Error(res.error || 'restart failed')
      notifications.show({
        color: 'teal',
        message: res.node_restart?.detail || 'Node restart started (RPC sleep)',
      })
      setRestartOpen(false)
    } catch (err) {
      notifications.show({ color: 'red', message: String((err as Error).message || err) })
    } finally {
      setRestartBusy(false)
    }
  }

  async function confirmNodeStart() {
    setStartBusy(true)
    try {
      const res = await api.nodeStart({ node: id })
      if (!res.ok) throw new Error(res.error || 'start failed')
      notifications.show({
        color: 'teal',
        message: res.node_restart?.detail || 'Node start accepted',
      })
      setStartOpen(false)
    } catch (err) {
      notifications.show({ color: 'red', message: String((err as Error).message || err) })
    } finally {
      setStartBusy(false)
    }
  }

  // Single grid-cell wrapper: Card + modals must not be sibling Fragment children
  // of SimpleGrid (each Fragment child becomes its own column → empty middle gaps).
  return (
    <div className={anchorId ? 'node-card-cell nodes-network-section' : 'node-card-cell'} id={anchorId}>
    <Card
      className="env-card env-card--installed"
      style={{ cursor: 'pointer', display: 'flex', flexDirection: 'column', height: '100%' }}
      onClick={() => navigate({ name: 'node', id })}
    >
      <Group justify="space-between" mb="sm" align="flex-start" style={{ flexShrink: 0 }}>
        <Group gap="sm">
          <ThemeIcon color={working ? 'teal' : color} variant="light" size="lg">
            {busy ? <Loader size={16} color={color} /> : <IconTopologyStar3 size={18} />}
          </ThemeIcon>
          <div>
            <Group gap={6} wrap="nowrap">
              <NetworkIcon network={network} size={16} />
              <Text fw={700}>{title}</Text>
              {stepParts.status ? (
                <Text size="sm" fw={600} c={`${color}.4`} tt="uppercase">
                  {stepParts.status}
                </Text>
              ) : null}
            </Group>
            <Text size="xs" c="dimmed">
              {network}/{env}
            </Text>
          </div>
        </Group>
        <Group gap={6} wrap="nowrap" style={{ flexShrink: 0 }}>
          <Tooltip label="Agent logs">
            <ActionIcon
              size="sm"
              variant="subtle"
              color="gray"
              aria-label="Agent logs"
              onClick={(e) => void openLogs(e)}
            >
              <IconFileText size={14} />
            </ActionIcon>
          </Tooltip>
          <Tooltip label="Restart">
            <ActionIcon
              size="sm"
              variant="subtle"
              color="gray"
              aria-label="Restart"
              loading={restartBusy}
              disabled={
                isRemoving ||
                !nodeRestartAllowed(
                  { status: model.status, agent_port: model.agentPort },
                  phase,
                )
              }
              onClick={(e) => {
                e.stopPropagation()
                setRestartOpen(true)
              }}
            >
              <IconRefresh size={14} />
            </ActionIcon>
          </Tooltip>
          {nodeStartAllowed({ status: model.status, agent_port: model.agentPort }, phase) ? (
            <Tooltip label="Start">
              <ActionIcon
                size="sm"
                variant="subtle"
                color="teal"
                aria-label="Start"
                loading={startBusy}
                disabled={isRemoving || startBusy}
                onClick={(e) => {
                  e.stopPropagation()
                  setStartOpen(true)
                }}
              >
                <IconPlayerPlay size={14} />
              </ActionIcon>
            </Tooltip>
          ) : (
            <Tooltip label="Stop">
              <ActionIcon
                size="sm"
                variant="subtle"
                color="gray"
                aria-label="Stop"
                loading={stopBusy}
                disabled={
                  isRemoving ||
                  !nodeStopAllowed(
                    { status: model.status, agent_port: model.agentPort },
                    phase,
                  )
                }
                onClick={(e) => {
                  e.stopPropagation()
                  setStopOpen(true)
                }}
              >
                <IconPlayerStop size={14} />
              </ActionIcon>
            </Tooltip>
          )}
          <Tooltip label="Remove">
            <ActionIcon
              size="sm"
              variant="subtle"
              color="red"
              aria-label="Remove"
              onClick={(e) => {
                e.stopPropagation()
                onRemove()
              }}
            >
              <IconTrash size={14} />
            </ActionIcon>
          </Tooltip>
          <Badge
            color={isRemoving ? 'orange' : color}
            variant="light"
            leftSection={
              busy || isRemoving ? (
                <Loader size={10} color={isRemoving ? 'orange' : color} />
              ) : undefined
            }
          >
            {isRemoving ? 'removing' : stepParts.count || label}
          </Badge>
        </Group>
      </Group>

      <Stack gap={6} className="node-card-body">
        {(isRemoving || isRemoveError || (phase === 'error') || (detail && !redundantDetail)) && (
        <div className="node-card__status">
        {isRemoving || isRemoveError ? (
          <Alert color="orange" variant="light" p="xs" icon={<IconTrash size={14} />}>
            <Text size="sm" fw={600}>
              {isRemoveError ? 'Remove error' : 'Removing…'}
            </Text>
            <Text size="xs" c="dimmed">
              {detail ||
                (isRemoveError
                  ? 'Tip did not ACK — open Remove and Retry (tip resumes kill → units → wipe)'
                  : 'Tip removing in background — Retry if stuck')}
            </Text>
          </Alert>
        ) : phase === 'error' ? (
          <Alert color="red" variant="light" p="xs" icon={<IconAlertTriangle size={14} />}>
            <Text size="xs">{detail || 'Open node to retry setup'}</Text>
          </Alert>
        ) : (
          <Text
            size="sm"
            c={busy ? `${color}.4` : 'dimmed'}
            lineClamp={2}
            style={{ minHeight: 'calc(1.45em * 2)' }}
          >
            {detail}
          </Text>
        )}
        </div>
        )}

        <div className="node-card__sync">
        {!isRemoving && (
          <LifecycleStepper
            compact
            ready
            network={network}
            lifecycle={{
              phase,
              label: phase === 'working' ? 'Synced' : label,
              detail,
              busy,
              pct: phase === 'working' ? (progress ?? 100) : progress,
              height,
              complete: phase === 'working',
              ...(phase === 'working'
                ? { current: 'run', node_status: 'online' }
                : phase === 'syncing'
                  ? { current: 'ibd', node_status: 'syncing' }
                  : /snapshot/i.test(`${phase} ${label} ${detail || ''}`)
                    ? { current: 'snapshot', node_status: 'snapshot' }
                    : phase === 'starting' || phase === 'installing'
                      ? { current: 'start', node_status: 'starting' }
                      : {}),
            }}
          />
        )}
        </div>

        <div className="node-card__meta">
        <div className="node-card__client">
          <Text size="xs" c="dimmed" tt="uppercase" style={{ letterSpacing: 0.4 }}>
            Client
          </Text>
          <Group gap={6} align="center" wrap="nowrap">
            <Tooltip
              label={
                clientOutdated
                  ? `Update available → ${clientLatestLabel || 'newer'} (click to confirm)`
                  : clientCurrent
                    ? 'Re-apply latest client (click to confirm)'
                    : 'Client version unknown'
              }
            >
              <Text
                size="sm"
                fw={600}
                className="mono"
                c={clientColor}
                lineClamp={1}
                title={clientLabel}
                style={
                  clientKnown && clientUpdateClickable(phase)
                    ? { cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: 3 }
                    : undefined
                }
                onClick={(e) => {
                  if (!clientKnown || !clientUpdateClickable(phase)) return
                  e.stopPropagation()
                  setClientStarted(false)
                  setClientOpen(true)
                }}
              >
                {clientLabel}
              </Text>
            </Tooltip>
            {clientCurrent ? (
              <Badge
                color="teal"
                variant="light"
                size="sm"
                style={{
                  cursor: clientUpdateClickable(phase) ? 'pointer' : 'default',
                }}
                onClick={(e) => {
                  if (!clientUpdateClickable(phase)) return
                  e.stopPropagation()
                  setClientStarted(false)
                  setClientOpen(true)
                }}
              >
                latest
              </Badge>
            ) : null}
            {clientOutdated && clientLatestLabel ? (
              <Badge
                color="orange"
                variant="light"
                size="sm"
                style={{ cursor: clientUpdateClickable(phase) ? 'pointer' : 'default' }}
                onClick={(e) => {
                  if (!clientUpdateClickable(phase)) return
                  e.stopPropagation()
                  setClientStarted(false)
                  setClientOpen(true)
                }}
              >
                → {clientLatestLabel}
              </Badge>
            ) : null}
          </Group>
          {phase === 'updating' ? (
            <Text size="xs" c="yellow.4" mt={4}>
              Updating client…
            </Text>
          ) : null}
          {phase === 'restarting' ? (
            <Text size="xs" c="yellow.4" mt={4}>
              Restarting… public RPC sleeping (503)
            </Text>
          ) : null}
          {phase === 'stopping' ? (
            <Text size="xs" c="yellow.4" mt={4}>
              Stopping… public RPC sleeping (503)
            </Text>
          ) : null}
          {phase === 'starting' ? (
            <Text size="xs" c="yellow.4" mt={4}>
              Starting… public RPC sleeping (503)
            </Text>
          ) : null}
          {phase === 'stopped' ? (
            <Text size="xs" c="dimmed" mt={4}>
              Stopped — Start to start
            </Text>
          ) : null}
        </div>
        <div className="node-card__server">
          <Text size="xs" c="dimmed" tt="uppercase" style={{ letterSpacing: 0.4 }}>
            Server
          </Text>
          <Text
            size="sm"
            fw={600}
            className="node-card__server-name"
            title={serverHint || undefined}
          >
            {serverHint ? displayServerLabel(serverHint) : '—'}
          </Text>
        </div>
        <NodeLifecycleDates
          added={model.createdAt}
          install={model.installStartedAt}
          synced={model.syncedAt}
          updated={model.updatedAt}
        />
        </div>

        <Stack gap={2} className="node-card__metrics">
          {nodeNet &&
          (nodeNet.node_net_rx_mbps != null || nodeNet.node_net_tx_mbps != null) ? (
            <Text size="xs" c="dimmed" className="mono" lineClamp={1}>
              Net ↓ {fmtMbps(nodeNet.node_net_rx_mbps)} · ↑ {fmtMbps(nodeNet.node_net_tx_mbps)}
              {rpcProxy &&
              (rpcProxy.rps_1m != null || rpcProxy.latency_p95_ms != null)
                ? ` · RPC rps ${rpcProxy.rps_1m != null ? rpcProxy.rps_1m.toFixed(1) : '—'}`
                : ''}
            </Text>
          ) : rpcProxy &&
            (rpcProxy.rps_1m != null ||
              rpcProxy.latency_p95_ms != null ||
              rpcProxy.in_flight != null) ? (
            <Text size="xs" c="dimmed" className="mono" lineClamp={1}>
              Go RPC · rps {rpcProxy.rps_1m != null ? rpcProxy.rps_1m.toFixed(1) : '—'} · p95{' '}
              {rpcProxy.latency_p95_ms != null ? Math.round(rpcProxy.latency_p95_ms) : '—'}ms ·
              inflight {rpcProxy.in_flight ?? '—'}
              {rpcProxy.errors_5xx != null && rpcProxy.errors_5xx > 0
                ? ` · 5xx ${rpcProxy.errors_5xx}`
                : ''}
            </Text>
          ) : (
            <Text size="xs" c="dimmed" style={{ visibility: 'hidden' }} aria-hidden>
              —
            </Text>
          )}
        </Stack>

        <Group gap={6} className="node-card__footer">
          <Text size="xs" c={phase === 'error' ? 'red.4' : 'cyan.4'}>
            {phase === 'error'
              ? 'Open to fix / retry'
              : working
                ? `Open ${network.toUpperCase()} ops`
                : 'Open setup / status'}
          </Text>
          <IconArrowRight size={12} />
        </Group>
      </Stack>
    </Card>

    <div onClick={(e) => e.stopPropagation()}>
      {serverId ? (
        <ServerLogsModal
          opened={logsOpen}
          onClose={() => setLogsOpen(false)}
          serverId={serverId}
          serverName={serverHint || title}
          defaultStream="host"
          network={network}
          env={env}
        />
      ) : (
        <AgentLogsModal
          opened={logsOpen}
          onClose={() => setLogsOpen(false)}
          status={logsStatus}
          loading={logsLoading}
          title={`Logs · ${title}`}
        />
      )}
    </div>

    <Modal
      opened={restartOpen}
      onClose={() => (!restartBusy ? setRestartOpen(false) : undefined)}
      title="Restart fullnode?"
      centered
      onClick={(e) => e.stopPropagation()}
    >
      <Stack gap="md">
        <Text size="sm">
          Restart{' '}
          <Text span fw={700}>
            {network}/{env}
          </Text>
          . Public Go RPC will sleep (503) while the node unit restarts, then come back.
        </Text>
        <Alert color="yellow" variant="light" icon={<IconAlertTriangle size={16} />}>
          In-flight RPC clients will see temporary unavailability. Chain data is not wiped.
        </Alert>
        <Group justify="flex-end">
          <Button variant="default" disabled={restartBusy} onClick={() => setRestartOpen(false)}>
            Cancel
          </Button>
          <Button
            color="yellow"
            loading={restartBusy}
            leftSection={<IconRefresh size={14} />}
            onClick={() => void confirmNodeRestart()}
          >
            Restart fullnode
          </Button>
        </Group>
      </Stack>
    </Modal>

    <Modal
      opened={stopOpen}
      onClose={() => (!stopBusy ? setStopOpen(false) : undefined)}
      title="Stop fullnode?"
      centered
      onClick={(e) => e.stopPropagation()}
    >
      <Stack gap="md">
        <Text size="sm">
          Soft-stop{' '}
          <Text span fw={700}>
            {network}/{env}
          </Text>
          . Same graceful stop as Restart. Public Go RPC sleeps (503) until you Start.
        </Text>
        <Alert color="yellow" variant="light" icon={<IconAlertTriangle size={16} />}>
          The unit stays down. Chain data is not wiped.
        </Alert>
        <Group justify="flex-end">
          <Button variant="default" disabled={stopBusy} onClick={() => setStopOpen(false)}>
            Cancel
          </Button>
          <Button
            color="yellow"
            loading={stopBusy}
            leftSection={<IconPlayerStop size={14} />}
            onClick={() => void confirmNodeStop()}
          >
            Stop fullnode
          </Button>
        </Group>
      </Stack>
    </Modal>

    <Modal
      opened={startOpen}
      onClose={() => (!startBusy ? setStartOpen(false) : undefined)}
      title="Start fullnode?"
      centered
      onClick={(e) => e.stopPropagation()}
    >
      <Stack gap="md">
        <Text size="sm">
          Start{' '}
          <Text span fw={700}>
            {network}/{env}
          </Text>
          . Public Go RPC wakes after the node unit is up.
        </Text>
        <Alert color="yellow" variant="light" icon={<IconAlertTriangle size={16} />}>
          Chain data is not wiped.
        </Alert>
        <Group justify="flex-end">
          <Button variant="default" disabled={startBusy} onClick={() => setStartOpen(false)}>
            Cancel
          </Button>
          <Button
            color="teal"
            loading={startBusy}
            leftSection={<IconPlayerPlay size={14} />}
            onClick={() => void confirmNodeStart()}
          >
            Start fullnode
          </Button>
        </Group>
      </Stack>
    </Modal>

    <ClientUpdateModal
      opened={clientOpen}
      onClose={() => setClientOpen(false)}
      network={network}
      env={env}
      current={clientVersion}
      latest={clientLatest}
      updateAvailable={clientOutdated}
      allowed={clientUpdateAllowed(phase)}
      info={clientInfo}
      started={clientStarted}
      requestBusy={clientBusy}
      onStop={() => {
        setClientOpen(false)
        setStopOpen(true)
      }}
      onStart={() => void confirmClientUpdate()}
    />
    </div>
  )
}
