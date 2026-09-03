import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Code,
  Group,
  Modal,
  Stack,
  Text,
  Loader,
  Center,
  Tooltip,
} from '@mantine/core'
import { useMediaQuery } from '@mantine/hooks'
import {
  IconAlertTriangle,
  IconArrowLeft,
  IconCopy,
  IconFileText,
  IconLayoutSidebarRight,
  IconPlayerPlay,
  IconPlayerStop,
  IconRefresh,
  IconServer,
  IconSettings,
  IconFlask,
  IconTrash,
} from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import { api, type ClientUpdateInfo, type RegistryNode, type Workload, getJSONResult, type ApiCallResult } from '../api'
import { copyText } from '../lib/copyText'
import { blockProps } from '../lib/blockId'
import type { MetricsPayload, StatusPayload } from '../types'
import { AppChrome, AsideSection } from '../components/AppChrome'
import { useAsideShell } from '../components/PageAside'
import { MetricCharts } from '../components/MetricCharts'
import { snapshotUIMode } from '../components/SnapshotCard'
import { AgentErrorsPanel } from '../components/AgentErrorsPanel'
import { AgentInstallLogPanel } from '../components/AgentInstallLogPanel'
import {
  AgentLogsPanel,
  showAgentLogsPanel,
} from '../components/AgentLogsPanel'
import { NodeLogsModal } from '../components/NodeLogsModal'
import {
  SyncStatusCard,
  showSyncStatusCard,
} from '../components/SyncStatusCard'
import { NodeConfigPanel } from '../components/NodeConfigPanel'
import {
  NodeInstallWizard,
  needsInstallWizard,
} from '../components/NodeInstallWizard'
import { LifecycleStepper } from '../components/LifecycleStepper'
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
import { ClientUpdateModal } from '../components/ClientUpdateModal'
import { buildHeaderChips } from '../lib/labels'
import { NodeMetaAside } from '../components/NodeMetaAside'
import { NodePortsPanel } from '../components/NodePortsPanel'
import { NodeErrorsPanel } from '../components/NodeErrorsPanel'
import { ApiFetchIssue } from '../components/ApiFetchIssue'
import {
  deriveNodeLifecycle,
  nodeProcessRunning,
  nodeRestartAllowed,
  nodeStartAllowed,
  nodeStopAllowed,
  nodeLiveTestAllowed,
  resolveCurrentStep,
} from '../lib/nodeLifecycle'
import { isNodeUUID, navigate, nodeIdToEnv, nodeIdToNetwork } from '../lib/router'
import { workloadToStatusPayload } from '../lib/panelNodePoll'
import { appendHostMetricsHistory, serverMetricsToPayload } from '../lib/serverMetrics'

/**
 * Fullnode = Go RPC on the **confirmed** public_port from ports plan/confirm
 * (panel node.public_port), host from Agent URL. Never agent_port / agent URL port.
 */
function resolveFullnodeEndpoint(
  workload: Workload | null,
  serverURL: string | null,
): string | null {
  const publicPort = numPort(workload?.public_port)
  if (!publicPort) return null

  const agentPort = numPort(workload?.agent_port)
  if (agentPort && publicPort === agentPort) return null

  const raw = String(serverURL || '').trim()
  if (!raw) return null

  try {
    const u = new URL(raw)
    if (!u.hostname) return null

    return `${u.protocol}//${u.hostname}:${publicPort}`
  } catch {
    return null
  }
}

function numPort(v: unknown): number {
  const n = typeof v === 'number' ? v : parseInt(String(v || ''), 10)

  return Number.isFinite(n) && n > 0 ? n : 0
}

/** Node UUID + copy — left side of the header, under the title. */
function NodeIdSubtitle({ id }: { id: string }) {
  return (
    <Group gap={4} wrap="nowrap" align="center" style={{ minWidth: 0 }}>
      <Text size="xs" c="dimmed">
        ID
      </Text>
      <Code
        className="mono"
        style={{ fontSize: '0.75rem', overflow: 'hidden', textOverflow: 'ellipsis' }}
      >
        {id}
      </Code>
      <Tooltip label="Copy node ID">
        <ActionIcon
          size="xs"
          variant="subtle"
          color="gray"
          aria-label="Copy node ID"
          onClick={() => {
            void copyText(id)
              .then(() => {
                notifications.show({ color: 'teal', message: 'Node ID copied', autoClose: 2000 })
              })
              .catch(() => {
                notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
              })
          }}
        >
          <IconCopy size={12} />
        </ActionIcon>
      </Tooltip>
    </Group>
  )
}

function isAgentUnreachableStatus(st: StatusPayload | null | undefined): boolean {
  if (!st) return false
  // Real fault only after install/start — leaf agent should already be up.
  // Before Confirm ports / mid-setup: no banner (nothing to reach yet).
  if (st.needs_provision) return false
  const ns = (st.node_status || st.lifecycle?.node_status || '').toLowerCase()
  if (
    ns === 'awaiting_ports' ||
    ns === 'ports_confirmed' ||
    ns === 'ready_to_install' ||
    ns === 'installing'
  ) {
    return false
  }
  const phase = (st.ui_phase || st.lifecycle?.phase || '').toLowerCase()
  if (phase === 'ports' || phase === 'setup' || phase === 'install') return false
  return (
    st.agent_reachable === false ||
    st.error === 'agent_unreachable' ||
    (st.agent?.status === 'error' && st.agent?.activity === 'unreachable')
  )
}

type Props = { env: string; nodeId?: string }

export function EnvDetailPage({ env: envProp, nodeId }: Props) {
  const [workload, setWorkload] = useState<Workload | null>(null)
  const [workloadReady, setWorkloadReady] = useState(false)
  const [server, setServer] = useState<RegistryNode | null>(null)
  const [status, setStatus] = useState<StatusPayload | null>(null)
  const [statusReady, setStatusReady] = useState(false)
  /** After first paint, never remount the page on soft status/workload refresh. */
  const shellReadyRef = useRef(false)
  /** Avoid duplicate «not found» toasts under React Strict Mode. */
  const missingToastForRef = useRef('')
  const [metrics, setMetrics] = useState<MetricsPayload | null>(null)
  /** Rolling chart series from successive `server_metrics` snapshots (DB keeps latest only). */
  const [hostHistory, setHostHistory] = useState<MetricsPayload['history']>()
  const hostHistoryServerRef = useRef('')
  const [error, setError] = useState<string | null>(null)
  const [workloadFetchIssue, setWorkloadFetchIssue] = useState<ApiCallResult<{ item?: Workload }> | null>(null)
  const [heightFetchIssue, setHeightFetchIssue] = useState<ApiCallResult<unknown> | null>(null)
  /** Wizard reached Finish — hide NODE SETUP even if SQLite status lags (Kotlin panel). */
  const [setupComplete, setSetupComplete] = useState(false)
  const [removeOpen, setRemoveOpen] = useState(false)
  const [removeMode, setRemoveMode] = useState<RemoveNodeMode>('wipe')
  const [removing, setRemoving] = useState(false)
  const [removeTyped, setRemoveTyped] = useState('')
  const [restartOpen, setRestartOpen] = useState(false)
  const [restartBusy, setRestartBusy] = useState(false)
  const [stopOpen, setStopOpen] = useState(false)
  const [stopBusy, setStopBusy] = useState(false)
  const [startOpen, setStartOpen] = useState(false)
  const [startBusy, setStartBusy] = useState(false)
  const [configOpen, setConfigOpen] = useState(false)
  const [clientOpen, setClientOpen] = useState(false)
  const [clientBusy, setClientBusy] = useState(false)
  const [clientStarted, setClientStarted] = useState(false)
  const [clientInfo, setClientInfo] = useState<ClientUpdateInfo | null>(null)
  const [clientRollbackBusy, setClientRollbackBusy] = useState(false)
  const [logsOpen, setLogsOpen] = useState(false)
  const [testBusy, setTestBusy] = useState(false)
  const [testOpen, setTestOpen] = useState(false)
  const [testReport, setTestReport] = useState<{
    ok?: boolean
    error?: string
    checks?: Array<{ id?: string; title?: string; ok?: boolean; detail?: string; error?: string }>
  } | null>(null)
  const [nodeHeight, setNodeHeight] = useState<{
    status?: string
    height: number
    network_height?: number | null
    behind?: number | null
    sync_pct?: number | null
  } | null>(null)
  const { toggleMobile, mobileOpen } = useAsideShell()
  const asideDocked = useMediaQuery('(min-width: 75em)') ?? true
  // Never default network=tron — that briefly forwarded TRON disk/snapshot_error
  // onto BSC/bitcoin leaf agents before workload loaded.
  const network =
    workload?.network ||
    nodeIdToNetwork(nodeId || '') ||
    ''
  const env =
    workload?.env ||
    nodeIdToEnv(nodeId || '') ||
    envProp ||
    ''
  const removePhrase = removeConfirmPhrase(network, env)
  const removeConfirmed = removePhraseMatches(removeTyped, removePhrase)
  useEffect(() => {
    if (removeOpen) {
      setRemoveTyped('')
      setRemoveMode('wipe')
    }
  }, [removeOpen])
  useEffect(() => {
    setSetupComplete(false)
  }, [nodeId, workload?.id])
  // Primary key is UUID; never invent network-env slug for status proxy.
  const workloadId = workload?.id || nodeId || ''
  const configTarget = useMemo(
    () => (workloadId ? { node: workloadId } : null),
    [workloadId],
  )
  const targetKey = workloadId || (network && env ? `${network}/${env}` : '')
  const targetRef = useRef(targetKey)
  targetRef.current = targetKey

  // UUID pages: wait for panel workload (network/env) before first status poll.
  const statusTargetReady =
    !!workloadId &&
    (!isNodeUUID(nodeId || '') || !!(workload?.network && workload?.env))

  async function confirmNodeStop() {
    if (!workloadId) return
    setStopBusy(true)
    try {
      const res = await api.workloadsNodeProcessStop(workloadId)
      if (res.ok === false) throw new Error(res.message || res.error || 'stop failed')
      notifications.show({
        color: 'teal',
        message: res.action ? `Node ${res.action}` : 'Node stopped',
      })
      setStopOpen(false)
      void reloadWorkload({ soft: true })
    } catch (err) {
      notifications.show({ color: 'red', message: String((err as Error).message || err) })
    } finally {
      setStopBusy(false)
    }
  }

  async function confirmNodeRestart() {
    if (!workloadId) return
    setRestartBusy(true)
    try {
      // Panel process/start re-applies the unit and restarts (no tip /api/v1/node/*).
      const res = await api.workloadsNodeProcessStart(workloadId)
      if (res.ok === false) throw new Error(res.message || res.error || 'restart failed')
      notifications.show({
        color: 'teal',
        message:
          res.pid != null
            ? `Node restarted (pid ${res.pid})`
            : res.action
              ? `Node ${res.action}`
              : 'Node restart accepted',
      })
      setRestartOpen(false)
      void reloadWorkload({ soft: true })
    } catch (err) {
      notifications.show({ color: 'red', message: String((err as Error).message || err) })
    } finally {
      setRestartBusy(false)
    }
  }

  async function runLiveTest() {
    if (!workloadId) return
    setTestBusy(true)
    try {
      const res = await api.workloadsTest({ node_id: workloadId })
      setTestReport(res)
      setTestOpen(true)
      const st = (res.live_test_status || (res.ok ? 'pass' : 'fail')).toLowerCase()
      setWorkload((prev) =>
        prev
          ? {
              ...prev,
              live_test_status: st === 'pass' ? 'pass' : 'fail',
              live_test_at: res.live_test_at || new Date().toISOString(),
              live_test_error: st === 'pass' ? '' : res.live_test_error || res.error || '',
            }
          : prev,
      )
      if (res.ok) {
        notifications.show({ color: 'teal', message: 'Node tests passed' })
      } else {
        notifications.show({
          color: 'red',
          message: res.error || res.message || 'Node tests failed',
          autoClose: 8000,
        })
      }
    } catch (err) {
      notifications.show({ color: 'red', message: String((err as Error).message || err) })
    } finally {
      setTestBusy(false)
    }
  }

  async function confirmNodeStart() {
    if (!workloadId) return
    setStartBusy(true)
    try {
      const res = await api.workloadsNodeProcessStart(workloadId)
      if (res.ok === false) throw new Error(res.message || res.error || 'start failed')
      notifications.show({
        color: 'teal',
        message:
          res.pid != null
            ? `Node started (pid ${res.pid})`
            : res.action
              ? `Node ${res.action}`
              : 'Node start accepted',
      })
      setStartOpen(false)
      void reloadWorkload({ soft: true })
    } catch (err) {
      notifications.show({ color: 'red', message: String((err as Error).message || err) })
    } finally {
      setStartBusy(false)
    }
  }

  async function confirmClientUpdate() {
    if (!workloadId) return
    setClientBusy(true)
    setClientStarted(true)
    try {
      const res = await api.clientUpdate(workloadId)
      if (res.ok === false) throw new Error(res.message || res.error || 'client update failed')
      if (res.client_update) setClientInfo(res.client_update)
      void reloadWorkload({ soft: true })
    } catch (err) {
      setClientStarted(false)
      notifications.show({ color: 'red', message: String((err as Error).message || err) })
    } finally {
      setClientBusy(false)
    }
  }

  async function confirmClientRollback() {
    if (!workloadId) return
    setClientRollbackBusy(true)
    try {
      const res = await api.clientUpdateRollback(workloadId)
      if (res.ok === false) throw new Error(res.message || res.error || 'rollback failed')
      if (res.client_update) setClientInfo(res.client_update)
      notifications.show({
        color: 'teal',
        message: res.client_update?.local
          ? `Enabled previous client ${res.client_update.local}`
          : 'Previous client enabled',
      })
      void reloadWorkload({ soft: true })
    } catch (err) {
      notifications.show({ color: 'red', message: String((err as Error).message || err) })
    } finally {
      setClientRollbackBusy(false)
    }
  }

  useEffect(() => {
    if (!clientOpen || !workloadId) return
    let stop = false
    const tick = async () => {
      try {
        const res = await api.clientInfo(workloadId)
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
  }, [clientOpen, workloadId])

  async function confirmRemove() {
    if (!workloadId) return
    setRemoving(true)
    try {
      const req = removeModeToRequest(removeMode)
      const res = await api.workloadsRemove({
        id: workloadId,
        ...req,
        force: false,
      })
      if (!res.ok) throw new Error(res.message || res.error || 'remove failed')
      notifications.show({
        color: 'teal',
        title: removeMode === 'panel' ? 'Removed from panel' : 'Removing…',
        message:
          removeMode === 'panel'
            ? `${network}/${env} — panel row dropped; host was not changed`
            : removeMode === 'agents'
              ? `${network}/${env} — tip stops the node and leaf agents; chain data stays`
              : `${network}/${env} — tip wipes folders; row stays until they are gone`,
      })
      setRemoveOpen(false)
      navigate({ name: 'nodes' })
    } catch (e) {
      notifications.show({
        color: 'red',
        title: 'Remove failed',
        message: String((e as Error).message || e),
        autoClose: 12_000,
      })
      await reloadWorkload({ soft: true })
    } finally {
      setRemoving(false)
    }
  }

  const reloadWorkload = useCallback(async (opts?: { soft?: boolean }) => {
    const id = (nodeId || '').trim()
    if (!id) {
      setWorkload(null)
      setServer(null)
      setWorkloadReady(true)
      return
    }
    // Soft refresh (Confirm ports / wizard) must not blank the page — that remounts
    // the wizard, drops portsConfirming, and flashes loader → ports → loader → install.
    const soft = !!opts?.soft
    if (!soft) setWorkloadReady(false)
    try {
      let hit: Workload | null = null
      let fetchIssue: ApiCallResult<{ item?: Workload }> | null = null
      const onePath = `/api/nodes/${encodeURIComponent(id)}`
      const one = await getJSONResult<{ item?: Workload }>(onePath)
      fetchIssue = one
      if (one.ok && one.data.item) {
        hit = one.data.item
      } else if (!one.ok) {
        const listPath = '/api/nodes'
        const list = await getJSONResult<{ items?: Workload[] }>(listPath)
        if (!list.ok) {
          fetchIssue = list
        } else {
          const net = nodeIdToNetwork(id)
          const wantEnv = nodeIdToEnv(id)
          hit =
            list.data.items?.find((w) => w.id === id) ||
            (net && wantEnv
              ? list.data.items?.find((w) => w.network === net && w.env === wantEnv) || null
              : null) ||
            null
          if (!hit) {
            fetchIssue = one
          }
        }
      }
      setWorkloadFetchIssue(hit ? null : fetchIssue)
      setWorkload(hit)

      // Old slug bookmark → rewrite URL to UUID once.
      if (hit?.id && !isNodeUUID(id) && isNodeUUID(hit.id) && hit.id !== id) {
        window.history.replaceState({}, '', `/nodes/${encodeURIComponent(hit.id)}`)
      }

      if (!hit) {
        if (!soft) {
          setServer(null)
          if (missingToastForRef.current !== id) {
            missingToastForRef.current = id
            notifications.show({
              color: 'orange',
              title: 'Node not found',
              message: fetchIssue
                ? `${fetchIssue.request} → ${fetchIssue.status}: ${fetchIssue.message || fetchIssue.error || 'failed'}`
                : `No node «${id}» in this panel`,
              autoClose: 8000,
            })
          }
        }
        return
      }
      missingToastForRef.current = ''

      const serverId = hit.server_id
      if (serverId) {
        const reg = await api.registryList().catch(() => null)
        setServer(reg?.items?.find((s) => s.id === serverId) || null)
      } else {
        setServer(null)
      }
    } catch {
      if (!soft) {
        setWorkload(null)
        setServer(null)
      }
    } finally {
      setWorkloadReady(true)
    }
  }, [nodeId])

  const wlStatus = (workload?.status || '').toLowerCase()
  const heightEligible = !!workloadId
  const showNodeErrors =
    wlStatus === 'sync' ||
    wlStatus === 'active' ||
    wlStatus === 'start_error' ||
    wlStatus === 'snapshot_error' ||
    wlStatus.endsWith('_error')

  useEffect(() => {
    if (!workloadId || !heightEligible) {
      setNodeHeight(null)
      return
    }
    let cancelled = false
    async function poll() {
      const path = `/api/nodes/${encodeURIComponent(workloadId)}/height`
      const res = await getJSONResult<{
        ok?: boolean
        height?: number | null
        status?: string
        network_height?: number | null
        behind?: number | null
        sync_pct?: number | null
        error?: string
        message?: string
      }>(path)
      if (cancelled) return
      if (!res.ok) {
        setHeightFetchIssue(res)
        setNodeHeight(null)
        return
      }
      setHeightFetchIssue(null)
      if (res.data.ok === false || res.data.height == null) {
        setNodeHeight(null)
        return
      }
      setNodeHeight({
        status: res.data.status,
        height: Number(res.data.height),
        network_height: res.data.network_height ?? null,
        behind: res.data.behind ?? null,
        sync_pct: res.data.sync_pct ?? null,
      })
      if (res.data.status === 'active' && wlStatus === 'sync') {
        void reloadWorkload({ soft: true })
      }
    }
    void poll()
    const t = window.setInterval(() => void poll(), 10_000)
    return () => {
      cancelled = true
      window.clearInterval(t)
    }
  }, [workloadId, heightEligible, wlStatus, reloadWorkload])

  useEffect(() => {
    setWorkloadReady(false)
    void reloadWorkload()
  }, [reloadWorkload])

  const serverLabel = server?.name || server?.id || workload?.server_id || null
  const serverURL = server?.agent_url || null

  useEffect(() => {
    setStatus(null)
    setMetrics(null)
    setHostHistory(undefined)
    hostHistoryServerRef.current = ''
    setError(null)
    setStatusReady(false)
  }, [targetKey])

  // Panel uses SQLite workload + dedicated endpoints (no legacy agent status.json).
  useEffect(() => {
    if (!statusTargetReady) return
    setStatusReady(true)
  }, [statusTargetReady])

  const chips = useMemo(() => (status ? buildHeaderChips(status) : []), [status])
  // Kotlin panel has no status.json — synthesize lifecycle/sync from the SQLite row.
  // Prefer live /height poll (sync_pct) over stale list fields while IBD is running.
  const viewStatus = useMemo((): StatusPayload | null => {
    const base: StatusPayload | null = status
      ? status
      : workload
        ? workloadToStatusPayload(workload, !!workload.needs_snapshot)
        : null
    if (!base) return null
    if (
      nodeHeight?.sync_pct == null ||
      !Number.isFinite(nodeHeight.sync_pct) ||
      nodeHeight.sync_pct < 0
    ) {
      return base
    }
    const syncPct = nodeHeight.sync_pct
    return {
      ...base,
      sync: {
        ...(base.sync || {}),
        syncing: syncPct < 99.9 || (base.sync?.syncing ?? false),
        ibd: syncPct < 99.9 || (base.sync?.ibd ?? false),
        verification_pct: syncPct,
        blocks: nodeHeight.height ?? base.sync?.blocks,
        headers: nodeHeight.network_height ?? base.sync?.headers,
      },
      rpc: {
        ...(base.rpc || {}),
        node_height: nodeHeight.height,
        network_height: nodeHeight.network_height ?? undefined,
      },
    }
  }, [status, workload, nodeHeight])
  const lifecycle = useMemo(
    () => deriveNodeLifecycle(viewStatus, workload?.status, network, workload?.needs_snapshot),
    [viewStatus, workload?.status, workload?.needs_snapshot, network],
  )
  const agentUnreachable = useMemo(() => isAgentUnreachableStatus(status), [status])
  const currentStep = useMemo(
    () => resolveCurrentStep(viewStatus?.lifecycle),
    [viewStatus?.lifecycle],
  )
  const snapMode = viewStatus ? snapshotUIMode(viewStatus, network, env) : 'hidden'
  // Install rail until Active (snapshot_error / start_error / sync stay in NODE SETUP).
  const showWizard = !setupComplete && needsInstallWizard(viewStatus, workload)
  const fullnodeEndpoint = useMemo(
    () => resolveFullnodeEndpoint(workload, serverURL),
    [workload, serverURL],
  )
  // Fullnode Go RPC only in ops mode (after Healthy) — never during setup wizard.
  const showFullnodeRpcPanel = !showWizard && !!workload?.public_port && !status?.needs_provision
  // Agent → panel `/api/agent/v1/metrics` → registry `server.metrics` (no tip /metrics.json).
  const hostMetrics = useMemo(
    () =>
      serverMetricsToPayload(server?.metrics, {
        updatedAt: server?.metrics?.collected_at || server?.metrics?.last_seen_at,
        status: server?.metrics_status,
      }),
    [server?.metrics, server?.metrics_status],
  )

  // server_metrics is one row per host — fold polls into chart history.
  useEffect(() => {
    const serverId = (workload?.server_id || '').trim()
    const switched = serverId !== hostHistoryServerRef.current
    if (switched) {
      hostHistoryServerRef.current = serverId
    }
    if (!hostMetrics?.current) {
      if (switched) setHostHistory(undefined)
      return
    }
    setHostHistory((prev) =>
      appendHostMetricsHistory(
        switched ? undefined : prev,
        hostMetrics.current!,
        hostMetrics.updated_at,
      ),
    )
  }, [workload?.server_id, hostMetrics])

  const metricsView = useMemo(() => {
    const base = metrics || hostMetrics
    if (!base) return null
    if (!hostHistory) return base
    return { ...base, history: hostHistory }
  }, [metrics, hostMetrics, hostHistory])

  // Keep host metrics fresh from panel cache (agent heartbeat ~5s).
  useEffect(() => {
    const serverId = (workload?.server_id || '').trim()
    if (!serverId || showWizard) return
    let cancelled = false
    async function refreshServer() {
      const reg = await api.registryList().catch(() => null)
      if (cancelled || !reg) return
      setServer(reg.items?.find((s) => s.id === serverId) || null)
    }
    const t = window.setInterval(() => void refreshServer(), 5_000)
    return () => {
      cancelled = true
      window.clearInterval(t)
    }
  }, [workload?.server_id, showWizard])

  // Show shell once initial workload fetch finished (even if node row missing).
  if (workloadReady && (workload || nodeId)) {
    shellReadyRef.current = true
  }
  const pageLoading = !shellReadyRef.current && !workloadReady

  if (pageLoading) {
    return (
      <Center mih="100vh">
        <Stack align="center" gap="sm">
          <Loader color="teal" />
          <Text c="dimmed">Loading node…</Text>
        </Stack>
      </Center>
    )
  }

  return (
    <AppChrome
      block="node.detail"
      title={`${(() => {
        const net = String(workload?.network || status?.lifecycle?.profile?.network || 'node')
        return net.charAt(0).toUpperCase() + net.slice(1)
      })()} · ${status?.view_env || status?.env || env}`}
      subtitle={workloadId && isNodeUUID(workloadId) ? <NodeIdSubtitle id={workloadId} /> : undefined}
      rightMeta={
        nodeHeight ? (
          <Text size="sm" className="mono" c="dimmed" ta="right">
            h{nodeHeight.height.toLocaleString()}
            {nodeHeight.network_height != null ? (
              <>
                {' '}
                · tip {Number(nodeHeight.network_height).toLocaleString()}
                {nodeHeight.behind != null && nodeHeight.behind > 0 ? (
                  <> · {Number(nodeHeight.behind).toLocaleString()} behind</>
                ) : nodeHeight.status === 'active' ||
                  (nodeHeight.behind != null && nodeHeight.behind === 0) ? (
                  <> · at tip</>
                ) : null}
              </>
            ) : null}
          </Text>
        ) : undefined
      }
      /*
       * Node metadata first, then what the operator reads while the left column
       * works: logs, install walk, classified errors. The header keeps the
       * title and the actions only.
       */
      aside={
        <>
          <AsideSection block="node.detail.meta" label="node">
            <NodeMetaAside
              workload={workload}
              status={status}
              serverLabel={serverLabel}
              tipAgentVersion={server?.agent_version}
              phase={lifecycle.phase}
              fullnodeEndpoint={fullnodeEndpoint}
              onClientUpdate={() => {
                setClientStarted(false)
                setClientOpen(true)
              }}
            />
          </AsideSection>
          {workloadId && isNodeUUID(workloadId) ? (
            <AsideSection block="node.detail.ports" label="ports">
              <NodePortsPanel
                nodeId={workloadId}
                serverId={workload?.server_id || ''}
                network={workload?.network || ''}
                env={workload?.env || ''}
                liveWhenRunning={nodeProcessRunning(workload)}
              />
            </AsideSection>
          ) : null}
          {status ? (
            <>
              {lifecycle.phase !== 'working' && showAgentLogsPanel(status) ? (
                <AsideSection block="node.detail.log" label="node log">
                  <AgentLogsPanel status={status} />
                </AsideSection>
              ) : null}
              {!showWizard ? (
                <AsideSection block="node.detail.install-log" label="install">
                  <AgentInstallLogPanel status={status} />
                </AsideSection>
              ) : null}
            </>
          ) : null}
          {workloadId && isNodeUUID(workloadId) ? (
            <AsideSection block="node.detail.errors" label="errors">
              <Stack gap="sm">
                {status ? <AgentErrorsPanel status={status} /> : null}
                <NodeErrorsPanel
                  nodeId={workloadId}
                  network={network}
                  env={env}
                  liveTestError={workload?.live_test_error}
                  autoFetch={showNodeErrors}
                />
              </Stack>
            </AsideSection>
          ) : null}
        </>
      }
      right={
        <Group gap={6} wrap="nowrap" justify="flex-end" align="center" className="page-chrome__actions">
          {!asideDocked ? (
            <Tooltip label={mobileOpen ? 'Hide details panel' : 'Show details · logs · errors'}>
              <ActionIcon
                size="sm"
                variant={mobileOpen ? 'filled' : 'light'}
                color="gray"
                aria-label={mobileOpen ? 'Hide details panel' : 'Show details panel'}
                aria-pressed={mobileOpen}
                onClick={toggleMobile}
              >
                <IconLayoutSidebarRight size={16} />
              </ActionIcon>
            </Tooltip>
          ) : null}
          {serverLabel && (
            <Tooltip label={`Server ${serverLabel}`}>
              <ActionIcon
                size="sm"
                variant="light"
                color="gray"
                aria-label={`Server ${serverLabel}`}
                onClick={() => navigate({ name: 'servers' })}
              >
                <IconServer size={16} />
              </ActionIcon>
            </Tooltip>
          )}
          {workloadId ? (
            <Tooltip label="Node logs">
              <ActionIcon
                size="sm"
                variant="light"
                color="gray"
                aria-label="Node logs"
                onClick={() => setLogsOpen(true)}
              >
                <IconFileText size={16} />
              </ActionIcon>
            </Tooltip>
          ) : null}
          {workloadId && (
            <Tooltip label="Config">
              <ActionIcon
                size="sm"
                variant="light"
                color="gray"
                aria-label="Config"
                disabled={
                  showWizard ||
                  agentUnreachable ||
                  !!status?.needs_provision ||
                  ['awaiting_ports', 'ready_to_install', 'ports_confirmed', 'installing', 'removing'].includes(
                    String(workload?.status || status?.node_status || '').toLowerCase(),
                  )
                }
                onClick={() => setConfigOpen(true)}
              >
                <IconSettings size={16} />
              </ActionIcon>
            </Tooltip>
          )}
          {workloadId && nodeLiveTestAllowed(workload, lifecycle.phase, status?.node_restart?.phase) ? (
            <Tooltip
              label={
                (workload?.live_test_status || '') === 'fail'
                  ? workload?.live_test_error || 'Tests failed'
                  : (workload?.live_test_status || '') === 'pass'
                    ? 'Tests passed'
                    : 'Test node'
              }
            >
              <ActionIcon
                size="sm"
                variant="light"
                color={
                  (workload?.live_test_status || '') === 'fail'
                    ? 'red'
                    : (workload?.live_test_status || '') === 'pass'
                      ? 'teal'
                      : 'gray'
                }
                aria-label="Test node"
                loading={testBusy}
                disabled={testBusy}
                onClick={() => void runLiveTest()}
              >
                <IconFlask size={16} />
              </ActionIcon>
            </Tooltip>
          ) : null}
          {workloadId && (
            <Tooltip label="Restart fullnode">
              <ActionIcon
                size="sm"
                variant="light"
                color="gray"
                aria-label="Restart fullnode"
                loading={restartBusy}
                disabled={
                  !nodeRestartAllowed(workload, lifecycle.phase) ||
                  ['restarting', 'stopping', 'starting'].includes(
                    (status?.node_restart?.phase || '').toLowerCase(),
                  )
                }
                onClick={() => setRestartOpen(true)}
              >
                <IconRefresh size={16} />
              </ActionIcon>
            </Tooltip>
          )}
          {workloadId &&
            (nodeStartAllowed(workload, lifecycle.phase, status?.node_restart?.phase) ? (
              <Tooltip label="Start fullnode">
                <ActionIcon
                  size="sm"
                  variant="light"
                  color="teal"
                  aria-label="Start fullnode"
                  loading={startBusy}
                  disabled={startBusy}
                  onClick={() => setStartOpen(true)}
                >
                  <IconPlayerPlay size={16} />
                </ActionIcon>
              </Tooltip>
            ) : (
              <Tooltip label="Stop fullnode">
                <ActionIcon
                  size="sm"
                  variant="light"
                  color="gray"
                  aria-label="Stop fullnode"
                  loading={stopBusy}
                  disabled={
                    !nodeStopAllowed(workload, lifecycle.phase, status?.node_restart?.phase) ||
                    stopBusy
                  }
                  onClick={() => setStopOpen(true)}
                >
                  <IconPlayerStop size={16} />
                </ActionIcon>
              </Tooltip>
            ))}
          {workloadId && (
            <Tooltip
              label={
                (workload?.status || '').toLowerCase() === 'removing' ||
                (workload?.status || '').toLowerCase() === 'remove_error'
                  ? 'Retry remove'
                  : 'Remove node'
              }
            >
              <ActionIcon
                size="sm"
                variant="light"
                color="red"
                aria-label={
                  (workload?.status || '').toLowerCase() === 'removing' ||
                  (workload?.status || '').toLowerCase() === 'remove_error'
                    ? 'Retry remove'
                    : 'Remove node'
                }
                onClick={() => {
                  setRemoveOpen(true)
                }}
              >
                <IconTrash size={16} />
              </ActionIcon>
            </Tooltip>
          )}
          <Tooltip label="Back to Nodes">
            <ActionIcon
              size="sm"
              variant="default"
              color="gray"
              aria-label="Back to Nodes"
              onClick={() => navigate({ name: 'nodes' })}
            >
              <IconArrowLeft size={16} />
            </ActionIcon>
          </Tooltip>
        </Group>
      }
    >
      <Stack gap="md" mt="md" className="node-detail-page" {...blockProps('node.detail.content')}>
        {workloadFetchIssue ? (
          <ApiFetchIssue title="Could not load node from panel" result={workloadFetchIssue} />
        ) : null}
        {heightFetchIssue ? (
          <ApiFetchIssue title="Node height request failed" result={heightFetchIssue} />
        ) : null}
        {agentUnreachable && (
          <Alert
            color="orange"
            icon={<IconAlertTriangle size={16} />}
            title="Agent unreachable"
          >
            Showing last known status from panel cache
            {status?.cached_at ? ` · cached ${status.cached_at}` : ''}.
            {status?.message || status?.agent?.last_error ? (
              <Text size="xs" c="dimmed" mt={6} className="mono" style={{ wordBreak: 'break-all' }}>
                {status.message || status.agent?.last_error}
              </Text>
            ) : null}
          </Alert>
        )}
        {lifecycle.phase === 'error' && !agentUnreachable && (
          <Alert
            color="red"
            icon={<IconAlertTriangle size={16} />}
            title={lifecycle.label}
          >
            {lifecycle.detail}
            {status?.agent?.status && (
              <Text size="xs" c="dimmed" mt={6}>
                Agent: {status.agent.status}
                {status.agent.activity ? ` · ${status.agent.activity}` : ''}
              </Text>
            )}
          </Alert>
        )}

        {/*
          FIXED ORDER on node detail — do not swap these two:
            1) Sync status
            2) NODE SETUP (wizard) / lifecycle
          Host / Fullnode Go RPC charts come after — never between them.
        */}
        {viewStatus && (showSyncStatusCard(viewStatus, network) || serverLabel || serverURL) ? (
          <div {...blockProps('node.detail.sync')}>
            <SyncStatusCard
              status={viewStatus}
              network={network}
              serverLabel={serverLabel}
              serverURL={serverURL}
              serverOs={server?.os_pretty}
            />
          </div>
        ) : null}

        {showWizard ? (
          <NodeInstallWizard
            env={env}
            workload={workload}
            status={viewStatus}
            statusReady={statusReady}
            serverLabel={serverLabel}
            serverURL={serverURL}
            server={server}
            onRefresh={() => reloadWorkload({ soft: true })}
            onWorkloadUpdated={() => reloadWorkload({ soft: true })}
            onSetupComplete={() => {
              const st = (workload?.status || '').toLowerCase()
              if (st !== 'active' && st !== 'online') return
              setSetupComplete(true)
              void reloadWorkload({ soft: true })
            }}
          />
        ) : (
          <div {...blockProps('node.detail.lifecycle')}>
          <>
            {(status || workload || !statusReady) && lifecycle.phase !== 'working' && (
              <Stack gap="xs">
                {statusReady && currentStep && (
                  <Badge size="lg" variant="light" color="yellow" w="fit-content">
                    {currentStep.countLabel}
                    {snapMode !== 'hidden' && currentStep.pct != null
                      ? ` · ${String(currentStep.pct)}%`
                      : ''}
                  </Badge>
                )}
                <LifecycleStepper
                  status={viewStatus}
                  lifecycle={viewStatus?.lifecycle}
                  workloadStatus={workload?.status}
                  network={network}
                  env={env}
                  needsSnapshot={workload?.needs_snapshot}
                  ready={statusReady}
                />
              </Stack>
            )}

            <Group gap="xs" wrap="wrap">
              <Badge color={lifecycle.color} variant="light" size="sm">
                {lifecycle.label}
                {lifecycle.height != null ? ` · h${lifecycle.height}` : ''}
              </Badge>
              {chips.map((c) => (
                <Badge key={c.key} color={c.color} variant="light" size="sm">
                  {c.label}
                </Badge>
              ))}
            </Group>
            <Text size="sm" c="dimmed">
              {lifecycle.detail}
            </Text>

            {(status?.pause?.active || error) && (
              <Alert
                color={error ? 'red' : 'yellow'}
                icon={<IconAlertTriangle size={16} />}
                title={error ? 'Polling error' : status?.pause?.title || 'Maintenance'}
              >
                {error || status?.pause?.message}
              </Alert>
            )}

          </>
          </div>
        )}

        {!showWizard ? (
          <div {...blockProps('node.detail.metrics')}>
            <MetricCharts
              metrics={metricsView}
              statusMetrics={status?.metrics}
              cachedRpc={workload?.rpc_proxy}
              forceRpcPanel={showFullnodeRpcPanel}
              showRpcPanel
            />
          </div>
        ) : null}

      </Stack>

      {configTarget && network && env ? (
        <NodeConfigPanel
          mode="modal"
          opened={configOpen}
          onClose={() => setConfigOpen(false)}
          target={configTarget}
          network={network}
          env={env}
          nodeId={workload?.id}
          enabled={
            !agentUnreachable &&
            !status?.needs_provision &&
            !['awaiting_ports', 'ready_to_install', 'ports_confirmed', 'installing', 'removing'].includes(
              String(workload?.status || status?.node_status || '').toLowerCase(),
            )
          }
          onApplied={() => {
            void reloadWorkload({ soft: true })
          }}
        />
      ) : null}

      {workloadId ? (
        <NodeLogsModal
          opened={logsOpen}
          onClose={() => setLogsOpen(false)}
          nodeId={workloadId}
          title={
            workload?.name ||
            (network && env ? `${network}/${env}` : workloadId)
          }
        />
      ) : null}

      <Modal
        {...blockProps('modal.node-live-test')}
        opened={testOpen}
        onClose={() => setTestOpen(false)}
        title={testReport?.ok ? 'Node tests passed' : 'Node tests failed'}
        centered
      >
        <Stack gap="sm">
          {!testReport?.ok && testReport?.error ? (
            <Alert color="red" icon={<IconAlertTriangle size={16} />}>
              {testReport.error}
            </Alert>
          ) : null}
          {(testReport?.checks || []).map((c) => (
            <Group key={c.id || c.title} justify="space-between" wrap="nowrap" align="flex-start">
              <div>
                <Text size="sm" fw={600}>
                  {c.title || c.id}
                </Text>
                <Text size="xs" c="dimmed">
                  {c.ok ? c.detail || 'ok' : c.error || c.detail || 'failed'}
                </Text>
              </div>
              <Badge color={c.ok ? 'teal' : 'red'} variant="light">
                {c.ok ? 'pass' : 'fail'}
              </Badge>
            </Group>
          ))}
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setTestOpen(false)}>
              Close
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Modal
        {...blockProps('modal.restart-node')}
        opened={restartOpen}
        onClose={() => (!restartBusy ? setRestartOpen(false) : undefined)}
        title="Restart fullnode?"
        centered
      >
        <Stack gap="md">
          <Text size="sm">
            Restart{' '}
            <Text span fw={700}>
              {network}/{env}
            </Text>
            . Public Go RPC will sleep (503) while the node unit restarts, then come back.
            Works when the unit is down or failed (retry start after a bad conf).
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
        {...blockProps('modal.stop-node')}
        opened={stopOpen}
        onClose={() => (!stopBusy ? setStopOpen(false) : undefined)}
        title="Stop fullnode?"
        centered
      >
        <Stack gap="md">
          <Text size="sm">
            Soft-stop{' '}
            <Text span fw={700}>
              {network}/{env}
            </Text>
            . Same graceful stop as Restart (CLI / ExecStop / SIGTERM). Public Go RPC
            sleeps (503) until you Start.
          </Text>
          <Alert color="yellow" variant="light" icon={<IconAlertTriangle size={16} />}>
            The unit stays down. Chain data is not wiped. Use Start to bring it up.
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
        {...blockProps('modal.start-node')}
        opened={startOpen}
        onClose={() => (!startBusy ? setStartOpen(false) : undefined)}
        title="Start fullnode?"
        centered
      >
        <Stack gap="md">
          <Text size="sm">
            Start{' '}
            <Text span fw={700}>
              {network}/{env}
            </Text>
            . Public RPC wakes after the node unit is up.
          </Text>
          <Alert color="yellow" variant="light" icon={<IconAlertTriangle size={16} />}>
            Chain data is not wiped. Same start as after Install.
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
        current={status?.client_version || workload?.client_version}
        latest={workload?.client_latest || clientInfo?.latest || status?.client_update?.latest}
        updateAvailable={
          !!workload?.client_update_available ||
          !!clientInfo?.update_available ||
          !!status?.client_update?.update_available
        }
        info={clientInfo || status?.client_update}
        started={clientStarted}
        requestBusy={clientBusy}
        rollbackBusy={clientRollbackBusy}
        onStart={() => void confirmClientUpdate()}
        onRollback={() => void confirmClientRollback()}
      />

      <Modal
        {...blockProps('modal.remove-node')}
        opened={removeOpen}
        onClose={() => (!removing ? setRemoveOpen(false) : undefined)}
        title="Remove node?"
        centered
        size="md"
      >
        <Stack gap="md">
          <Text size="sm">
            Remove{' '}
            <Text span fw={700}>
              {network}/{env}
            </Text>
          </Text>
          {(workload?.status || '').toLowerCase() === 'removing' ||
          (workload?.status || '').toLowerCase() === 'remove_error' ? (
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
            <Button variant="default" disabled={removing} onClick={() => setRemoveOpen(false)}>
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
                (workload?.status || '').toLowerCase() === 'removing' ||
                  (workload?.status || '').toLowerCase() === 'remove_error',
              )}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </AppChrome>
  )
}
