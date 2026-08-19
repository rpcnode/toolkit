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
import {
  IconAlertTriangle,
  IconArrowLeft,
  IconCopy,
  IconFileText,
  IconPlayerStop,
  IconRefresh,
  IconServer,
  IconSettings,
  IconTrash,
} from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import { api, type RegistryNode, type Workload } from '../api'
import type { MetricsPayload, StatusPayload } from '../types'
import { copyText } from '../lib/copyText'
import { maskHostInURL } from '../lib/maskHost'
import { AppChrome } from '../components/AppChrome'
import { MetricCharts } from '../components/MetricCharts'
import { snapshotUIMode } from '../components/SnapshotCard'
import {
  AgentLogsModal,
  AgentLogsPanel,
  showAgentLogsPanel,
} from '../components/AgentLogsPanel'
import { ServerLogsModal } from '../components/ServerLogsModal'
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
import { formatClientVersion } from '../lib/format'
import { buildHeaderChips } from '../lib/labels'
import { NodeLifecycleDates } from '../components/NodeLifecycleDates'
import {
  deriveNodeLifecycle,
  clientUpdateAllowed,
  nodeRestartAllowed,
  nodeStopAllowed,
  resolveCurrentStep,
} from '../lib/nodeLifecycle'
import { isNodeUUID, navigate, nodeIdToEnv, nodeIdToNetwork } from '../lib/router'

const POLL_MS = 2000

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

  const raw = String(serverURL || workload?.agent_url || '').trim()
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

/** True when payload has nothing useful to render (empty unreachable shell). */
function isEmptyUnreachableShell(st: StatusPayload | null | undefined): boolean {
  if (!st || !isAgentUnreachableStatus(st)) return false
  const hasLifecycle = !!(st.lifecycle?.phase || st.lifecycle?.steps?.length)
  const hasHeight = st.rpc?.node_height != null || st.lifecycle?.height != null
  const hasSync = !!st.sync
  const hasLogs = !!(st.logs?.lines && st.logs.lines.length > 0)
  return !hasLifecycle && !hasHeight && !hasSync && !hasLogs && !st.cached
}

/**
 * Prefer panel-cached / prior status over an empty unreachable shell so the UI
 * never resets sync/lifecycle/metrics to zeros while the agent is down.
 */
function mergeStatusKeepLastKnown(
  prev: StatusPayload | null,
  next: StatusPayload,
): StatusPayload {
  if (!isEmptyUnreachableShell(next) || !prev) return next
  return {
    ...prev,
    ...next,
    lifecycle: prev.lifecycle ?? next.lifecycle,
    rpc: prev.rpc ?? next.rpc,
    sync: prev.sync ?? next.sync,
    logs: prev.logs ?? next.logs,
    snapshot: prev.snapshot ?? next.snapshot,
    services: prev.services ?? next.services,
    checks: prev.checks ?? next.checks,
    disk: prev.disk ?? next.disk,
    agent_reachable: false,
    degraded: true,
    cached: true,
    error: next.error || 'agent_unreachable',
    message: next.message || prev.message,
    note: next.note || 'Agent unreachable — showing last known status.',
    agent: next.agent || {
      status: 'error',
      activity: 'unreachable',
      last_error: next.message,
    },
  }
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
  const [metrics, setMetrics] = useState<MetricsPayload | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [removeOpen, setRemoveOpen] = useState(false)
  const [removeMode, setRemoveMode] = useState<RemoveNodeMode>('wipe')
  const [removing, setRemoving] = useState(false)
  const [removeTyped, setRemoveTyped] = useState('')
  const [restartOpen, setRestartOpen] = useState(false)
  const [restartBusy, setRestartBusy] = useState(false)
  const [stopOpen, setStopOpen] = useState(false)
  const [stopBusy, setStopBusy] = useState(false)
  const [configOpen, setConfigOpen] = useState(false)
  const [clientOpen, setClientOpen] = useState(false)
  const [clientBusy, setClientBusy] = useState(false)
  const [logsOpen, setLogsOpen] = useState(false)
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
      const res = await api.nodeStop({ node: workloadId })
      if (!res.ok) throw new Error(res.error || 'stop failed')
      notifications.show({
        color: 'teal',
        message: res.node_restart?.detail || 'Node stop started (RPC sleep)',
      })
      setStopOpen(false)
      void tick()
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
      const res = await api.nodeRestart({ node: workloadId })
      if (!res.ok) throw new Error(res.error || 'restart failed')
      notifications.show({
        color: 'teal',
        message: res.node_restart?.detail || 'Node restart started (RPC sleep)',
      })
      setRestartOpen(false)
      void tick()
    } catch (err) {
      notifications.show({ color: 'red', message: String((err as Error).message || err) })
    } finally {
      setRestartBusy(false)
    }
  }

  async function confirmClientUpdate() {
    if (!workloadId) return
    setClientBusy(true)
    try {
      await api.clientCheck({ node: workloadId })
      const res = await api.clientUpdate({ node: workloadId })
      if (!res.ok) throw new Error(res.error || 'client update failed')
      notifications.show({
        color: 'teal',
        message: res.client_update?.detail || 'Client update started (RPC sleep)',
      })
      setClientOpen(false)
      void tick()
    } catch (err) {
      notifications.show({ color: 'red', message: String((err as Error).message || err) })
    } finally {
      setClientBusy(false)
    }
  }

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
              : `${network}/${env} — tip runs kill → units → wipe in background`,
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
      const one = await api.workloadsGet(id).catch(() => null)
      if (one?.item) {
        hit = one.item
      } else {
        const list = await api.workloadsList().catch(() => null)
        const net = nodeIdToNetwork(id)
        const wantEnv = nodeIdToEnv(id)
        hit =
          list?.items?.find((w) => w.id === id) ||
          (net && wantEnv
            ? list?.items?.find((w) => w.network === net && w.env === wantEnv) || null
            : null) ||
          null
      }
      setWorkload(hit)

      // Old slug bookmark → rewrite URL to UUID once.
      if (hit?.id && !isNodeUUID(id) && isNodeUUID(hit.id) && hit.id !== id) {
        window.history.replaceState({}, '', `/nodes/${encodeURIComponent(hit.id)}`)
      }

      const serverId = hit?.server_id
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

  useEffect(() => {
    setWorkloadReady(false)
    void reloadWorkload()
  }, [reloadWorkload])

  const serverLabel = server?.name || server?.id || workload?.server_id || null
  const serverURL = server?.agent_url || workload?.agent_url || null

  const tick = useCallback(async () => {
    if (!statusTargetReady) return
    const keyAtStart = targetKey
    // Do not pass server= alone — host CP may still identify as tron (or share
    // :39390 when CP is merged). Prefer node= so status uses per-node agent_port.
    // Only send network/env once known from the panel workload row.
    const target = {
      node: workloadId,
      ...(network ? { network } : {}),
      ...(env ? { env } : {}),
    }
    try {
      const [st, met] = await Promise.all([
        api.status(target),
        api.metrics(target).catch(() => null),
      ])
      // Ignore stale responses after env/node navigation.
      if (targetRef.current !== keyAtStart) return
      // Empty unreachable shell must not wipe a previously good live/cached view.
      setStatus((prev) => mergeStatusKeepLastKnown(prev, st))
      if (met) setMetrics(met)
      setError(null)
    } catch (e) {
      if (targetRef.current !== keyAtStart) return
      setError(String((e as Error).message || e))
    } finally {
      if (targetRef.current === keyAtStart) {
        setStatusReady(true)
      }
    }
  }, [targetKey, workloadId, network, env, statusTargetReady])

  useEffect(() => {
    setStatus(null)
    setMetrics(null)
    setError(null)
    setStatusReady(false)
  }, [targetKey])

  useEffect(() => {
    if (!statusTargetReady) return
    void tick()
    const id = window.setInterval(() => void tick(), POLL_MS)
    return () => window.clearInterval(id)
  }, [tick, statusTargetReady])

  const chips = useMemo(() => (status ? buildHeaderChips(status) : []), [status])
  const lifecycle = useMemo(
    () => deriveNodeLifecycle(status, workload?.status, network),
    [status, workload?.status, network],
  )
  const agentUnreachable = useMemo(() => isAgentUnreachableStatus(status), [status])
  const currentStep = useMemo(
    () => resolveCurrentStep(status?.lifecycle),
    [status?.lifecycle],
  )
  const snapMode = status ? snapshotUIMode(status, network) : 'hidden'
  // Install rail only for first-time provision / catch-up — not restart / client update.
  const showWizard = needsInstallWizard(status, workload)
  const fullnodeEndpoint = useMemo(
    () => resolveFullnodeEndpoint(workload, serverURL),
    [workload, serverURL],
  )
  // Fullnode Go RPC only in ops mode (after Healthy) — never during setup wizard.
  const showFullnodeRpcPanel = !showWizard && !!workload?.public_port && !status?.needs_provision

  // Wait for workload + first status poll before any panels (avoids wizard → full flash).
  // Once painted, keep mounted across Confirm ports soft refresh / tip needs_provision churn.
  if (
    workload &&
    (status || statusReady || error || !statusTargetReady || workloadReady)
  ) {
    shellReadyRef.current = true
  }
  const pageLoading =
    !shellReadyRef.current &&
    ((!workloadReady && !workload) ||
      (statusTargetReady && !statusReady && !status && !error))

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
      title={`${(() => {
        const net = String(workload?.network || status?.lifecycle?.profile?.network || 'node')
        return net.charAt(0).toUpperCase() + net.slice(1)
      })()} · ${status?.view_env || status?.env || env}`}
      subtitle={
        <Stack gap={4}>
        <Text c="dimmed" size="xs">
          {showWizard ? 'Setup wizard' : 'Network UI'}
          {workload?.name ? (
            <>
              {' '}
              ·{' '}
              <Text span fw={600} c="gray.3">
                {workload.name}
              </Text>
            </>
          ) : null}
          {serverLabel ? (
            <>
              {' '}
              · server{' '}
              <Text span fw={600} c="gray.3">
                {serverLabel}
              </Text>
            </>
          ) : null}
          {' · '}
          client{' '}
          {(() => {
            const ver = formatClientVersion(
              status?.client_version ||
                status?.rpc?.client_version ||
                status?.rpc?.version ||
                workload?.client_version ||
                '',
            )
            const latest = formatClientVersion(
              workload?.client_latest || status?.client_update?.latest || '',
            )
            const outdated =
              !!ver &&
              (!!workload?.client_update_available ||
                !!status?.client_update?.update_available)
            const color = !ver ? 'gray.3' : outdated ? 'orange.4' : 'teal.4'
            const canUpdate =
              clientUpdateAllowed(lifecycle.phase) && (!!ver || !!latest)
            const openUpdate = canUpdate ? () => setClientOpen(true) : undefined
            return (
              <>
                <Text
                  span
                  fw={600}
                  c={color}
                  className="mono"
                  style={
                    canUpdate
                      ? { cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: 2 }
                      : undefined
                  }
                  title={
                    outdated
                      ? `Update available → ${latest || 'newer'} (click to confirm)`
                      : ver
                        ? 'Re-apply latest client (click to confirm)'
                        : 'Client version unknown'
                  }
                  onClick={openUpdate}
                >
                  {ver || '—'}
                  {outdated && latest ? ` → ${latest}` : ''}
                </Text>
                {ver && !outdated ? (
                  <>
                    {' '}
                    ·{' '}
                    <Text
                      span
                      fw={600}
                      c="teal.4"
                      style={
                        canUpdate
                          ? { cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: 2 }
                          : undefined
                      }
                      title="Re-apply latest client (click to confirm)"
                      onClick={openUpdate}
                    >
                      latest
                    </Text>
                  </>
                ) : null}
              </>
            )
          })()}
          {' · '}
          <NodeLifecycleDates
            inline
            added={workload?.created_at}
            install={workload?.install_started_at}
            synced={workload?.synced_at}
            updated={status?.served_at || status?.updated_at || workload?.updated_at}
          />
        </Text>
        </Stack>
      }
      right={
        <Group gap="xs" wrap="nowrap">
          {serverLabel && (
            <Button
              size="xs"
              variant="light"
              color="gray"
              leftSection={<IconServer size={14} />}
              onClick={() => navigate({ name: 'servers' })}
            >
              {serverLabel}
            </Button>
          )}
          <Tooltip label="Agent logs">
            <ActionIcon
              size="md"
              variant="light"
              color="gray"
              aria-label="Agent logs"
              onClick={() => setLogsOpen(true)}
            >
              <IconFileText size={16} />
            </ActionIcon>
          </Tooltip>
          {workloadId && (
            <Button
              size="xs"
              variant="light"
              color="gray"
              leftSection={<IconSettings size={14} />}
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
              Config
            </Button>
          )}
          {workloadId && (
            <Button
              size="xs"
              variant="light"
              color="gray"
              leftSection={<IconRefresh size={14} />}
              loading={restartBusy}
              disabled={
                !nodeRestartAllowed(workload, lifecycle.phase) ||
                ['restarting', 'stopping'].includes(
                  (status?.node_restart?.phase || '').toLowerCase(),
                )
              }
              onClick={() => setRestartOpen(true)}
            >
              Restart
            </Button>
          )}
          {workloadId && (
            <Button
              size="xs"
              variant="light"
              color="gray"
              leftSection={<IconPlayerStop size={14} />}
              loading={stopBusy}
              disabled={
                !nodeStopAllowed(workload, lifecycle.phase, status?.node_restart?.phase) ||
                stopBusy
              }
              onClick={() => setStopOpen(true)}
            >
              Stop
            </Button>
          )}
          {workloadId && (
            <Button
              size="xs"
              variant="light"
              color="red"
              leftSection={<IconTrash size={14} />}
              onClick={() => {
                setRemoveOpen(true)
              }}
            >
              {(workload?.status || '').toLowerCase() === 'removing' ||
              (workload?.status || '').toLowerCase() === 'remove_error'
                ? 'Retry remove'
                : 'Remove'}
            </Button>
          )}
          <Button
            size="xs"
            variant="default"
            leftSection={<IconArrowLeft size={14} />}
            onClick={() => navigate({ name: 'nodes' })}
          >
            Back to Nodes
          </Button>
        </Group>
      }
      rightMeta={
        fullnodeEndpoint ? (
          <Group gap={6} wrap="nowrap" maw={420} justify="flex-end">
            <Text size="xs" c="dimmed" style={{ flexShrink: 0 }}>
              Fullnode
            </Text>
            <Code
              className="mono"
              title="Hidden — use copy"
              style={{
                maxWidth: 320,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {maskHostInURL(fullnodeEndpoint)}
            </Code>
            <Tooltip label="Copy confirmed public_port (Go RPC)">
              <ActionIcon
                size="sm"
                variant="light"
                color="gray"
                aria-label="Copy fullnode endpoint"
                onClick={() => {
                  void copyText(fullnodeEndpoint)
                    .then(() => {
                      notifications.show({
                        color: 'teal',
                        message: 'Fullnode endpoint copied',
                        autoClose: 2000,
                      })
                    })
                    .catch(() => {
                      notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
                    })
                }}
              >
                <IconCopy size={14} />
              </ActionIcon>
            </Tooltip>
          </Group>
        ) : undefined
      }
    >
      <Stack gap="md" mt="md">
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
        {status && (showSyncStatusCard(status, network) || serverLabel || serverURL) ? (
          <SyncStatusCard
            status={status}
            network={network}
            serverLabel={serverLabel}
            serverURL={serverURL}
            serverOs={server?.os_pretty}
          />
        ) : null}

        {showWizard ? (
          <NodeInstallWizard
            env={env}
            workload={workload}
            status={status}
            statusReady={statusReady}
            serverLabel={serverLabel}
            serverURL={serverURL}
            server={server}
            onRefresh={tick}
            onWorkloadUpdated={() => reloadWorkload({ soft: true })}
          />
        ) : (
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
                  status={status}
                  lifecycle={status?.lifecycle}
                  workloadStatus={workload?.status}
                  network={network}
                  ready={statusReady}
                />
              </Stack>
            )}

            <Group gap="xs">
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

            {status &&
              lifecycle.phase !== 'working' &&
              showAgentLogsPanel(status) && <AgentLogsPanel status={status} />}
          </>
        )}

        <MetricCharts
          metrics={metrics}
          statusMetrics={status?.metrics}
          cachedRpc={workload?.rpc_proxy}
          forceRpcPanel={!showWizard && showFullnodeRpcPanel}
          showRpcPanel={!showWizard}
        />

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
            void tick()
            void reloadWorkload({ soft: true })
          }}
        />
      ) : null}

      {workload?.server_id ? (
        <ServerLogsModal
          opened={logsOpen}
          onClose={() => setLogsOpen(false)}
          serverId={workload.server_id}
          serverName={
            serverLabel ||
            workload.name ||
            `${network || 'node'}/${env || ''}`
          }
          defaultStream="host"
          network={network}
          env={env}
        />
      ) : (
        <AgentLogsModal
          opened={logsOpen}
          onClose={() => setLogsOpen(false)}
          status={status}
          title={
            workload?.name
              ? `Logs · ${workload.name}`
              : `Logs · ${network || 'node'}/${env || ''}`
          }
        />
      )}

      <Modal
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
            sleeps (503) until you Restart.
          </Text>
          <Alert color="yellow" variant="light" icon={<IconAlertTriangle size={16} />}>
            The unit stays down. Chain data is not wiped. Use Restart to start again.
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
        opened={clientOpen}
        onClose={() => (!clientBusy ? setClientOpen(false) : undefined)}
        title={
          workload?.client_update_available || status?.client_update?.update_available
            ? 'Update client?'
            : 'Re-apply latest client?'
        }
        centered
      >
        <Stack gap="md">
          <Text size="sm">
            {workload?.client_update_available || status?.client_update?.update_available
              ? 'Update'
              : 'Re-download and re-install'}{' '}
            <Text span fw={700}>
              {network}/{env}
            </Text>{' '}
            client{' '}
            <Text span className="mono" fw={600}>
              {formatClientVersion(status?.client_version || workload?.client_version || '') ||
                '—'}
            </Text>
            {(workload?.client_latest || status?.client_update?.latest) ? (
              <>
                {' '}
                →{' '}
                <Text span className="mono" fw={600} c="teal">
                  {formatClientVersion(
                    workload?.client_latest || status?.client_update?.latest || '',
                  )}
                </Text>
              </>
            ) : null}
            .
          </Text>
          {!(workload?.client_update_available || status?.client_update?.update_available) ? (
            <Text size="sm" c="dimmed">
              Already on latest — re-install only. Node stays stopped until Restart.
            </Text>
          ) : null}
          <Alert color="orange" variant="light" icon={<IconAlertTriangle size={16} />}>
            Node is stopped. Replace the client only — then Restart to start.
          </Alert>
          <Group justify="flex-end">
            <Button variant="default" disabled={clientBusy} onClick={() => setClientOpen(false)}>
              Cancel
            </Button>
            <Button color="orange" loading={clientBusy} onClick={() => void confirmClientUpdate()}>
              {workload?.client_update_available || status?.client_update?.update_available
                ? 'Update client'
                : 'Re-apply latest'}
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Modal
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
