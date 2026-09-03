import {
  ActionIcon,
  Accordion,
  Alert,
  Badge,
  Box,
  Button,
  Code,
  Group,
  Loader,
  Modal,
  Popover,
  Progress,
  Radio,
  Skeleton,
  Stack,
  Switch,
  Text,
  TextInput,
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
  IconHelp,
  IconPlayerStop,
  IconPlayerPlay,
  IconRefresh,
  IconArrowRight,
  IconX,
} from '@tabler/icons-react'
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import {
  api,
  type CheckedCatalogPort,
  type AgentTarget,
  type DiskRoleDef,
  type ClientConfigSpec,
  type HostDiskInfo,
  type HostDiskInsight,
  type HostNofileInfo,
  type HostMountInfo,
  type MultiDiskLayoutPlan,
  type RegistryNode,
  type SnapshotSizeHint,
  type Workload,
} from '../../api'
import type { StatusPayload } from '../../types'
import { copyText } from '../../lib/copyText'
import { blockProps } from '../../lib/blockId'
import { normalizeDiskLayoutRoles } from '../../lib/diskLayoutRoles'
import { agentVersionOutdated } from '../../lib/agentVersion'
import { formatSyncPct, pct } from '../../lib/format'
import { diskRolesFromNetworksCatalog, clientConfigFromNetworksCatalog, l1ParentFromNetworksCatalog, useNetworksCatalog } from '../../lib/networksCatalog'
import {
  kotlinPanelOpsReady,
  nodeReadyForOps,
  panelPastNodeSetup,
  snapshotBlockMessage,
  snapReady,
  snapshotDownloadLive,
  resolveCurrentStep,
  wizardStepFromAgentLifecycle,
} from '../../lib/nodeLifecycle'
import { waitPanelLifecycleAck, waitPanelSnapshotStopped } from '../../lib/panelNodePoll'
import {
  classifySetupError,
  retryActionForLane,
  setupLaneFailedId,
  setupLaneRetryLabel,
  snapshotStartsViaNode,
  wizardStepFromFailedLane,
  type SetupLaneId,
} from '../../lib/setupLane'
import { isSolanaNetwork, isXrplNetwork, supportsIbdStep, workloadNeedsSnapshot } from '../../lib/network'
import { agentLogLines } from '../AgentLogsPanel'
import { DiskLayoutPanel, DiskLayoutSection, diskLayoutTitleFor } from '../DiskLayoutPanel'
import { diskPlacements } from '../NodeDiskSummary'
import { HostDisksSection } from '../HostDiskInsights'
import { type XrplHistoryMode } from '../XrplHistoryPicker'
import {
  InstallOptionsPicker,
  fallbackInstallGroups,
  installOptionLabel,
  parseInstallOptionGroups,
  type InstallOptionGroup,
} from '../InstallOptionsPicker'
import { resolveSyncProgressPct } from '../SyncStatusCard'
import { InstallActivityPanel } from '../InstallActivityPanel'
import { InstallProgressModal, type InstallProgressOutcome } from '../InstallProgressModal'
import { portCheckStatus } from './steps/ports/PortHelpers'

import type {
  CatalogPortPolicy,
  NodeInstallWizardProps,
  PlannedPorts,
  SnapshotSpeedReading,
  UnsupportedCapability,
  WizardStepId,
} from './types'
import {
  isNodeTypeOptionGroup,
  stepIndex,
  wizardSteps,
  wizardStepFromPanelStatus,
  wizardVisibleStep,
} from './steps'
import {
  MULTI_DISK_NETWORKS,
  PORTS_CHECK_HELP,
  bindingForCatalogPortRole,
  busyListenWhoisCommands,
  catalogPortConfigEnabled,
  catalogPortConfigPolicy,
  detectUnsupportedCapability,
  diskLayoutHasSelection,
  formatPortBusy,
  formatSnapshotBytes,
  formatSnapshotSpeed,
  formatSolanaBuildPendingMessage,
  heightProgressPct,
  isCheckPortsTimeout,
  isOnline,
  optionalCatalogPorts,
  plannedPortsFromCatalog,
  portConfigInstallOptionKey,
  resolveClientConfigPreview,
  resolveInstallPorts,
  sleep,
  snapRunning,
  snapshotCanDownload,
  snapshotCanStop,
  stillSyncingInWizard,
  unusedFromInventory,
  usableDiskLayout,
} from './utils'

import type { WizardApi } from './wizardContext'

export function useWizardOrchestration({
  env,
  workload,
  status,
  statusReady = false,
  serverLabel,
  serverURL,
  server,
  onRefresh,
  onWorkloadUpdated,
  onSetupComplete,
}: NodeInstallWizardProps): WizardApi {

  const networksCatalog = useNetworksCatalog()
  const [uiStep, setUiStep] = useState<WizardStepId | null>(null)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [log, setLog] = useState<string[]>([])
  const [ports, setPorts] = useState<PlannedPorts | null>(null)
  const [portsLoading, setPortsLoading] = useState(false)
  const [nodePortsChecking, setNodePortsChecking] = useState(false)
  const [portsError, setPortsError] = useState<string | null>(null)
  const [checkedPorts, setCheckedPorts] = useState<CheckedCatalogPort[]>([])
  /** Real per-network fixed ports (Kotlin catalog), live-checked against the host agent —
   * used whenever the legacy tip-agent "plan ports" reply above is empty. */
  const [nodePortsCatalog, setNodePortsCatalog] = useState<CheckedCatalogPort[]>([])
  /** Fixed ports from clients/*.yml (GET /api/nodes/{id}/ports) — used for clientConfig preview. */
  const [programCatalogPorts, setProgramCatalogPorts] = useState<CatalogPortPolicy[]>([])
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
  const [clientConfig, setClientConfig] = useState<ClientConfigSpec | null>(null)
  const [diskRules, setDiskRules] = useState<string[]>([])
  const [diskSnapshotHint, setDiskSnapshotHint] = useState<SnapshotSizeHint | null>(null)
  const [diskLoading, setDiskLoading] = useState(false)
  const [diskError, setDiskError] = useState<string | null>(null)
  const [diskSaved, setDiskSaved] = useState<boolean | null>(null)
  const [diskSaving, setDiskSaving] = useState(false)
  const [startSaving, setStartSaving] = useState(false)
  const [startApplyError, setStartApplyError] = useState<string | null>(null)
  const [testConnectBusy, setTestConnectBusy] = useState<string | null>(null)
  const [testConnectResult, setTestConnectResult] = useState<
    Record<string, { ok: boolean; detail: string }>
  >({})
  const [l1ParentChoices, setL1ParentChoices] = useState<
    Array<{
      id: string
      kind: string
      label: string
      rpc: string
      beacon: string
      status?: string | null
      same_host?: boolean
    }>
  >([])
  const [l1ParentPickHelp, setL1ParentPickHelp] = useState<string | null>(null)
  const [l1ParentLoading, setL1ParentLoading] = useState(false)
  const [startBuildPending, setStartBuildPending] = useState<string | null>(null)
  const [buildLogLines, setBuildLogLines] = useState<string[]>([])
  const [buildLogPath, setBuildLogPath] = useState('')
  const buildLogScroller = useRef<HTMLDivElement | null>(null)
  const [nodeHeight, setNodeHeight] = useState<{
    status?: string
    height: number
    network_height?: number | null
    behind?: number | null
    sync_pct?: number | null
  } | null>(null)
  const [nodeLogLines, setNodeLogLines] = useState<string[]>([])
  const [nodeLogPath, setNodeLogPath] = useState('')
  const [nodeProcessBusy, setNodeProcessBusy] = useState(false)
  const [nodeProcessError, setNodeProcessError] = useState<string | null>(null)
  /** Optimistic Sync unit state; seeded from workload.status (sync/active vs stopped). */
  const [nodeUnitRunning, setNodeUnitRunning] = useState(false)
  const nodeLogScroller = useRef<HTMLDivElement | null>(null)
  const [snapshotPlan, setSnapshotPlan] = useState<Awaited<ReturnType<typeof api.nodeSnapshotPlan>> | null>(null)
  const [snapshotSourceId, setSnapshotSourceId] = useState('')
  const [snapshotSpeedById, setSnapshotSpeedById] = useState<Record<string, SnapshotSpeedReading>>({})
  const [snapshotPlanLoading, setSnapshotPlanLoading] = useState(false)
  const [snapshotPlanError, setSnapshotPlanError] = useState<string | null>(null)
  const [snapshotProgress, setSnapshotProgress] = useState<Awaited<ReturnType<typeof api.nodeSnapshotProgress>> | null>(null)
  const [snapshotStarting, setSnapshotStarting] = useState(false)
  const snapshotPollTimer = useRef<ReturnType<typeof setInterval> | null>(null)
  const [clientsSyncing, setClientsSyncing] = useState(false)
  const [clientsSynced, setClientsSynced] = useState(false)
  const [clientsError, setClientsError] = useState<string | null>(null)
  const [clientsFiles, setClientsFiles] = useState<string[]>([])
  const [clientsPath, setClientsPath] = useState<string | null>(null)
  const clientsAutoStarted = useRef(false)
  const diskSaveTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [xrplHistory, setXrplHistory] = useState<XrplHistoryMode>('weeks')
  const [installGroups, setInstallGroups] = useState<InstallOptionGroup[]>([])
  const [installOptions, setInstallOptions] = useState<Record<string, string>>({})
  const [killTarget, setKillTarget] = useState<CheckedCatalogPort | null>(null)
  const [killing, setKilling] = useState(false)
  const [stopSnapshotOpen, setStopSnapshotOpen] = useState(false)
  const [stoppingSnapshot, setStoppingSnapshot] = useState(false)
  /** True after operator Stop until Download snapshot starts or agent reports live again. */
  const [snapshotIdleAfterStop, setSnapshotIdleAfterStop] = useState(false)
  const [installModalOpen, setInstallModalOpen] = useState(false)
  const [installOutcome, setInstallOutcome] = useState<InstallProgressOutcome | null>(null)
  const [installError, setInstallError] = useState<string | null>(null)
  const autoStarted = useRef(false)
  const nodeStartSent = useRef(false)
  const portsFetched = useRef(false)
  const nodePortsLiveFetched = useRef(false)
  const [diskRows, setDiskRows] = useState<HostDiskInfo[]>([])
  const [diskNofile, setDiskNofile] = useState<HostNofileInfo | null>(null)
  /** Solana Start: live host /proc/sys vs Anza recommended (agent GET /api/v1/host/sysctl). */
  const [hostSysctl, setHostSysctl] = useState<{
    current: Record<string, number | null>
    recommended: Record<string, number>
    optionByKey: Record<string, string>
  } | null>(null)
  const [hostSysctlError, setHostSysctlError] = useState<string | null>(null)
  /** Local step only after agent ACK of the click that advanced it. */
  const agentAckedStep = useRef<WizardStepId | null>(null)
  /** Back on a failed step must win over the failed-lane / agent-lifecycle
   * override below — the operator went back on purpose (e.g. to fix disks).
   * Cleared the moment Install is pressed again, so real agent progress
   * resumes driving the step. */
  const manualBackToPorts = useRef(false)
  /** Operator went Snapshot → Disks to fix layout — must beat agentAckedStep / needs_snapshot. */
  const manualBackToDisks = useRef(false)
  /** Operator went Snapshot/Start → Node type — must beat agentAckedStep / needs_snapshot. */
  const manualBackToNodeType = useRef(false)
  /** Operator went Snapshot/Start → Clients — must beat needs_snapshot. */
  const manualBackToClients = useRef(false)
  const networkId = (workload?.network || '').toLowerCase()
  const wantsDiskLayout = MULTI_DISK_NETWORKS.has(networkId)
  const wantsL1ParentPicker = networkId === 'base' || networkId === 'arb'

  useEffect(() => {
    if (!wantsL1ParentPicker || !networkId || !env) {
      setL1ParentChoices([])
      setL1ParentPickHelp(null)
      return
    }
    const childEnv = (env || '').trim().toLowerCase()
    const l1Env =
      childEnv === 'sepolia' || childEnv === 'testnet' ? 'sepolia' : 'mainnet'
    const help = (l1ParentFromNetworksCatalog(networkId, env)?.pickHelp || '').trim() || null
    let cancelled = false
    setL1ParentLoading(true)
    setL1ParentPickHelp(help)
    void api
      .networksEthereumNodes({
        env: l1Env,
        status: 'active',
        server_id: workload?.server_id || undefined,
      })
      .then((res) => {
        if (cancelled) return
        const choices: Array<{
          id: string
          kind: string
          label: string
          rpc: string
          beacon: string
          status?: string | null
          same_host?: boolean
        }> = []
        const pub = res.public
        if (pub?.rpc) {
          choices.push({
            id: 'public',
            kind: 'public',
            label: pub.label || `Public · ${pub.rpc}`,
            rpc: pub.rpc,
            beacon: pub.beacon || '',
          })
        }
        for (const n of res.items || []) {
          const where = n.same_host ? 'this host' : n.rpc.replace(/^https?:\/\//, '').split('/')[0]
          const pubHint = (n.public_endpoint || '').trim()
          choices.push({
            id: `node:${n.id}`,
            kind: 'node',
            label: pubHint
              ? `${n.name} · ${n.status} · ${where} · public ${pubHint}`
              : `${n.name} · ${n.status} · ${where}`,
            rpc: n.rpc,
            beacon: n.beacon,
            status: n.status,
            same_host: !!n.same_host,
          })
        }
        setL1ParentChoices(choices)
      })
      .catch(() => {
        if (cancelled) return
        setL1ParentChoices([])
      })
      .finally(() => {
        if (!cancelled) setL1ParentLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [wantsL1ParentPicker, networkId, env, workload?.server_id, networksCatalog])

  function applyL1ParentChoice(choice: { rpc: string; beacon: string }) {
    setInstallOptions((prev) => ({
      ...prev,
      l1_rpc: choice.rpc,
      l1_beacon: choice.beacon,
    }))
    setTestConnectResult({})
  }
  const diskLayoutSelected = useMemo(
    () => !wantsDiskLayout || diskLayoutHasSelection(diskLayout || diskRecommended, diskRoles),
    [wantsDiskLayout, diskLayout, diskRecommended, diskRoles],
  )
  const wantsXrplHistory = isXrplNetwork(networkId)
  const nodeTypeOptionGroups = useMemo(
    () => installGroups.filter(isNodeTypeOptionGroup),
    [installGroups],
  )
  const snapshotOptionGroups = useMemo(
    () => installGroups.filter((g) => g.id === 'snapshot'),
    [installGroups],
  )
  const wantsNodeTypeStep = nodeTypeOptionGroups.length > 0
  const wantsInstallOptions = installGroups.length > 0
  const nodeTypeStepLabel =
    nodeTypeOptionGroups.length === 1
      ? nodeTypeOptionGroups[0].label || 'Node type'
      : 'Node type'

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
  const catalogPortsList = useMemo((): CheckedCatalogPort[] => {
    if (checkedPorts.length > 0) return checkedPorts
    if (nodePortsCatalog.length > 0) return nodePortsCatalog
    return [
      { port: pub || 0, role: 'public', label: 'Go RPC (proxy)', external: true },
      { port: agentPort || 0, role: 'agent', label: 'Node Agent API', external: true },
      { port: nodeHttp || 0, role: 'upstream', label: 'Upstream HTTP / RPC', external: false },
      { port: p2p || 0, role: 'p2p', label: 'P2P', external: true },
      ...(ports?.captive_core_http_query_port
        ? [
            {
              port: ports.captive_core_http_query_port,
              role: 'captive_core',
              label: 'Captive Core HTTP_QUERY',
              external: false,
            },
          ]
        : []),
      ...(ports?.admin_port
        ? [{ port: ports.admin_port, role: 'admin', label: 'RPC admin', external: false }]
        : []),
    ]
  }, [
    checkedPorts,
    nodePortsCatalog,
    pub,
    agentPort,
    nodeHttp,
    p2p,
    ports?.captive_core_http_query_port,
    ports?.admin_port,
  ])
  /** Full clients/*.yml ports for clientConfig — never the legacy public/agent/upstream stub. */
  const clientConfigPorts = useMemo(() => {
    const usable = (list: Array<{ port?: number; role?: string; label?: string }>) =>
      list.filter((p) => p.role && Number(p.port) > 0)
    const fromProgram = usable(programCatalogPorts)
    if (fromProgram.length > 0) return fromProgram
    const fromLive = usable(nodePortsCatalog)
    if (fromLive.length > 0) return fromLive
    return usable(catalogPortsList)
  }, [programCatalogPorts, nodePortsCatalog, catalogPortsList])
  const clientConfigRows = useMemo(
    () =>
      resolveClientConfigPreview(
        clientConfig,
        diskLayout || diskRecommended,
        clientConfigPorts,
        installOptions,
        snapshotPlan?.snapshot_types || [],
      ),
    [
      clientConfig,
      diskLayout,
      diskRecommended,
      clientConfigPorts,
      installOptions,
      snapshotPlan?.snapshot_types,
    ],
  )
  const sysctlKeyByOption = useMemo(() => {
    const m: Record<string, string> = {}
    for (const [key, opt] of Object.entries(hostSysctl?.optionByKey || {})) {
      if (opt) m[opt] = key
    }
    return m
  }, [hostSysctl])
  const hostSysctlBelowRecommended = useMemo(() => {
    if (!hostSysctl) return false
    return Object.entries(hostSysctl.recommended).some(([key, rec]) => {
      const cur = hostSysctl.current[key]
      return typeof cur === 'number' && typeof rec === 'number' && cur < rec
    })
  }, [hostSysctl])
  const selectedSnapshotSource = useMemo(
    () => snapshotPlan?.sources?.find((s) => s.id === snapshotSourceId) || null,
    [snapshotPlan?.sources, snapshotSourceId],
  )
  const snapshotViaNode = useMemo(
    () =>
      snapshotPlan?.via_node === true ||
      snapshotStartsViaNode(status, networkId),
    [snapshotPlan?.via_node, status, networkId],
  )
  const snapshotDownloadReady = useMemo(() => {
    if (!snapshotPlan?.dest_dir) return false
    if (snapshotViaNode) return true
    if (selectedSnapshotSource) {
      return selectedSnapshotSource.available === true && !!selectedSnapshotSource.url
    }
    return !!snapshotPlan.url
  }, [
    snapshotPlan?.dest_dir,
    snapshotPlan?.url,
    selectedSnapshotSource,
    snapshotViaNode,
  ])
  const optionalPortBindings = useMemo(
    () => optionalCatalogPorts(clientConfigPorts),
    [clientConfigPorts],
  )
  // The legacy tip-agent "plan ports" call (askAgentPorts → portsLoading/portsError/reachNote)
  // has nothing to say about Kotlin-managed nodes — it always 404s there. Once the real catalog
  // is in hand, judge ports purely from it instead of that unrelated failure.
  const usingLiveNodeCatalog = checkedPorts.length === 0 && nodePortsCatalog.length > 0
  const portsOverallStatus = useMemo((): 'ok' | 'fail' | 'pending' => {
    if (!usingLiveNodeCatalog) {
      if (portsLoading && !portsConfirming) return 'pending'
      if (portsError || reachNote) return 'fail'
    }
    if (catalogPortsList.length === 0) return 'pending'
    const statuses = catalogPortsList.map(portCheckStatus)
    if (statuses.some((s) => s === 'fail')) return 'fail'
    if (statuses.every((s) => s === 'ok')) return 'ok'
    return 'pending'
  }, [catalogPortsList, portsLoading, portsConfirming, portsError, reachNote, usingLiveNodeCatalog])
  const disksContinueReady = useMemo(() => {
    if (unsupported) return false
    if (diskSaving || portsConfirming) return false
    if (wantsDiskLayout && !diskLayoutSelected) return false
    if (usingLiveNodeCatalog) {
      return portsOverallStatus === 'ok' && !nodePortsChecking
    }
    return (
      !!(ports?.public_port || workload?.public_port) &&
      !!(ports?.agent_port || workload?.agent_port) &&
      !portsLoading &&
      !reachNote
    )
  }, [
    unsupported,
    diskSaving,
    portsConfirming,
    wantsDiskLayout,
    diskLayoutSelected,
    usingLiveNodeCatalog,
    portsOverallStatus,
    nodePortsChecking,
    ports?.public_port,
    ports?.agent_port,
    workload?.public_port,
    workload?.agent_port,
    portsLoading,
    reachNote,
  ])
  const currentStep = useMemo(
    () => resolveCurrentStep(status?.lifecycle),
    [status?.lifecycle],
  )
  // Prefer shared resolver (handles 0..1 fraction vs 0..100 percent).
  const syncProgress =
    heightProgressPct(nodeHeight) ?? resolveSyncProgressPct(status)
  const progress =
    syncProgress ??
    (status?.snapshot?.pct != null ? pct(status.snapshot.pct) : null) ??
    (currentStep?.pct != null ? pct(currentStep.pct as number | string) : null)
  const syncingInWizard =
    stillSyncingInWizard(status) ||
    (workload?.status || '').toLowerCase() === 'sync' ||
    (nodeHeight != null &&
      (nodeHeight.status === 'sync' ||
        (nodeHeight.sync_pct != null &&
          nodeHeight.sync_pct >= 0 &&
          nodeHeight.sync_pct < 99.9) ||
        (nodeHeight.behind ?? 1) > 0))

  const allowSnap = workloadNeedsSnapshot(workload, status, workload?.network, env)
  const steps = useMemo(() => {
    const list = wizardSteps(allowSnap, wantsNodeTypeStep)
    if (!wantsNodeTypeStep) return list
    return list.map((s) =>
      s.id === 'node_type' ? { ...s, label: nodeTypeStepLabel } : s,
    )
  }, [allowSnap, wantsNodeTypeStep, nodeTypeStepLabel])
  const stepIdxOf = (id: WizardStepId) => stepIndex(id, allowSnap, wantsNodeTypeStep)
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
    const wl = (workload?.status || '').toLowerCase()
    // Finish only from panel SQLite status — never connect.ready / height/tip.
    if (wl === 'active' || wl === 'online') return 'done'

    // Operator clicked Back off a failed step (e.g. to fix disk layout) —
    // that must stick until they press Install again, not get overridden by
    // the still-failed lifecycle the very next render.
    if (manualBackToPorts.current) return 'ports'
    if (manualBackToDisks.current) return 'disks'
    if (manualBackToNodeType.current) return 'node_type'
    if (manualBackToClients.current) return 'clients'

    // Lane error stays on that step (not Install). While a retry is in flight,
    // follow the live agent cursor instead of the stale failed_step.
    if (!portsConfirming && !running) {
      const failed = setupLaneFailedId(status, allowSnap, {
        portsError: usingLiveNodeCatalog ? null : portsError,
        wizardError: error,
      })
      if (failed) return wizardStepFromFailedLane(failed)
    }

    // Panel status outlives refresh (agent lifecycle / agentAckedStep do not).
    // Prefer it before localAck — Solana sync must not stay stuck on Snapshot after Start.
    // Exception: needs_clients must not trap after Continue advanced agentAckedStep (race
    // before workload soft-reload lands).
    const fromPanel = wizardStepFromPanelStatus(workload?.status, allowSnap)
    if (
      fromPanel &&
      fromPanel !== 'ports' &&
      !manualBackToPorts.current &&
      !manualBackToDisks.current &&
      !manualBackToNodeType.current &&
      !manualBackToClients.current
    ) {
      const ack = agentAckedStep.current
      const panelStaleClients =
        fromPanel === 'clients' &&
        !!ack &&
        ack !== 'clients' &&
        stepIdxOf(ack) > stepIdxOf('clients')
      if (!panelStaleClients) {
        return fromPanel
      }
    }

    const localAck =
      agentAckedStep.current &&
      agentAckedStep.current !== 'ports' &&
      agentAckedStep.current !== 'disks'
        ? agentAckedStep.current
        : null

    // Confirm ports in flight OR just ACK'd: never regress to Ports / Disks
    // when tip still reports needs_provision (that was the Confirm → loader flash).
    if (portsConfirming || localAck) {
      if (
        localAck &&
        allowSnap &&
        snapshotDownloadLive(status) &&
        !snapReady(status) &&
        !status?.needs_provision
      ) {
        return 'snapshot'
      }
      if (localAck) {
        const advancing = wizardStepFromAgentLifecycle(status, allowSnap)
        if (
          advancing &&
          advancing !== 'ports' &&
          !status?.needs_provision &&
          stepIdxOf(advancing) > stepIdxOf(localAck)
        ) {
          return advancing
        }
        return localAck
      }
      return allowSnap ? 'snapshot' : 'start'
    }

    // Gate: before provision, stay on Check ports → Disks until operator continues.
    const wlStatus = (workload?.status || '').toLowerCase()
    if (
      status?.needs_provision ||
      wlStatus === 'awaiting_ports' ||
      wlStatus === 'ready_to_install'
    ) {
      if (manualBackToPorts.current) return 'ports'
      if (manualBackToDisks.current) return 'disks'
      if (manualBackToNodeType.current) return 'node_type'
      if (uiStep === 'node_type') return 'node_type'
      if (uiStep === 'disks') return 'disks'
      return 'ports'
    }

    // Live aria2 / ExtraStep must stay on Snapshot — a stale marker or tip ACK
    // on Start used to checkmark Snapshot and hide the bar.
    if (allowSnap && snapshotDownloadLive(status) && !snapReady(status)) {
      return 'snapshot'
    }

    const fromAgent = wizardStepFromAgentLifecycle(status, allowSnap)
    if (fromAgent) {
      if (allowSnap && snapshotDownloadLive(status) && !snapReady(status)) {
        return 'snapshot'
      }
      if (
        agentAckedStep.current &&
        running &&
        stepIdxOf(agentAckedStep.current) >= stepIdxOf(fromAgent)
      ) {
        return agentAckedStep.current
      }
      return fromAgent
    }

    if (allowSnap && status?.snapshot?.failed) return 'snapshot'
    if (running && allowSnap && (snapRunning(status, snapshotIdleAfterStop) || snapReady(status))) {
      return snapReady(status) ? 'start' : 'snapshot'
    }
    if (running && !allowSnap && agentAckedStep.current === 'start') return 'start'

    if (fromPanel) return fromPanel

    if (statusReady) {
      if (uiStep === 'done' && !status && !kotlinPanelOpsReady(status, workload)) {
        const latePanel = wizardStepFromPanelStatus(workload?.status, allowSnap)
        if (latePanel) return latePanel
        if (panelPastNodeSetup(workload)) return 'sync'
      } else if (uiStep) {
        return uiStep
      }
      return 'ports'
    }
    return uiStep
  }, [
    status,
    running,
    uiStep,
    portsConfirming,
    statusReady,
    allowSnap,
    wantsNodeTypeStep,
    workload?.status,
    workload?.needs_snapshot,
    portsError,
    error,
    snapshotIdleAfterStop,
    usingLiveNodeCatalog,
  ])

  const active = wizardVisibleStep(derived, allowSnap)
  const setupCompleteNotified = useRef(false)

  useEffect(() => {
    setupCompleteNotified.current = false
  }, [workload?.id])

  useEffect(() => {
    if (active !== 'done' || setupCompleteNotified.current) return
    setupCompleteNotified.current = true
    onSetupComplete?.()
  }, [active, onSetupComplete])

  // Kotlin panel: promote Sync → Finish when height probe reports active.
  useEffect(() => {
    if (status) return
    const wl = (workload?.status || '').toLowerCase()
    if (wl !== 'active' && nodeHeight?.status !== 'active') return
    if (active === 'sync') {
      setUiStep('done')
    }
  }, [status, workload?.status, nodeHeight?.status, active])

  useEffect(() => {
    if (nodeProcessBusy) return
    const s = (workload?.status || '').toLowerCase()
    if (s === 'stopped') {
      setNodeUnitRunning(false)
      return
    }
    if (s === 'sync' || s === 'active') {
      setNodeUnitRunning(true)
    }
  }, [workload?.status, nodeProcessBusy])

  useEffect(() => {
    const id = workload?.id
    if (!id || active !== 'sync') {
      setNodeHeight(null)
      return
    }
    let cancelled = false
    async function poll() {
      try {
        const res = await api.workloadsNodeHeight(id!)
        if (cancelled) return
        if (res.ok === false || res.height == null) {
          setNodeHeight(null)
          return
        }
        setNodeHeight({
          status: res.status,
          height: Number(res.height),
          network_height: res.network_height ?? null,
          behind: res.behind ?? null,
          sync_pct: res.sync_pct ?? null,
        })
        if (res.status === 'active') {
          onWorkloadUpdated?.()
          onRefresh?.()
        }
      } catch {
        if (!cancelled) setNodeHeight(null)
      }
    }
    void poll()
    const t = window.setInterval(() => void poll(), 10_000)
    return () => {
      cancelled = true
      window.clearInterval(t)
    }
  }, [active, workload?.id, onRefresh, onWorkloadUpdated])

  /** Solana Start: tail host `.toolkit/agave-build.log` while cargo-build runs. */
  useEffect(() => {
    const id = workload?.id
    const showBuildLog = active === 'start' && isSolanaNetwork(networkId) && !!id
    if (!showBuildLog) {
      if (active !== 'start') {
        setBuildLogLines([])
        setBuildLogPath('')
      }
      return
    }
    let cancelled = false
    async function pollBuildLog() {
      try {
        const res = await api.workloadsNodeLogs(id!, {
          lines: 150,
          logFile: '.toolkit/agave-build.log',
        })
        if (cancelled) return
        if (res.ok === false || !Array.isArray(res.lines)) {
          return
        }
        setBuildLogLines(res.lines)
        setBuildLogPath(res.path || '.toolkit/agave-build.log')
      } catch (e) {
        if (cancelled) return
        const msg = String((e as Error).message || e)
        // Keep last real tail; only seed a waiting line when still empty.
        setBuildLogLines((prev) =>
          prev.length > 0 || !/no_log_yet|not_found|Log not present|Conflict|409/i.test(msg)
            ? prev
            : [`(waiting) ${msg}`],
        )
      }
    }
    void pollBuildLog()
    const t = window.setInterval(() => void pollBuildLog(), 3_000)
    return () => {
      cancelled = true
      window.clearInterval(t)
    }
  }, [active, workload?.id, networkId, startBuildPending])

  useEffect(() => {
    const id = workload?.id
    if (!id || active !== 'sync') {
      setNodeLogLines([])
      setNodeLogPath('')
      return
    }
    let cancelled = false
    async function pollLogs() {
      try {
        const opts = isSolanaNetwork(networkId)
          ? { lines: 200, logFile: 'logs/validator.log' }
          : { lines: 200 }
        const res = await api.workloadsNodeLogs(id!, opts)
        if (cancelled) return
        if (res.ok === false || !Array.isArray(res.lines)) {
          return
        }
        setNodeLogLines(res.lines)
        setNodeLogPath(res.path || '')
      } catch (e) {
        if (cancelled) return
        const msg = String((e as Error).message || e)
        setNodeLogLines((prev) =>
          prev.length > 0 ? prev : [`(no log yet) ${msg}`],
        )
      }
    }
    void pollLogs()
    const t = window.setInterval(() => void pollLogs(), 5_000)
    return () => {
      cancelled = true
      window.clearInterval(t)
    }
  }, [active, workload?.id, networkId])

  useLayoutEffect(() => {
    if (active !== 'sync' || nodeLogLines.length === 0) return
    const el = nodeLogScroller.current
    if (!el) return
    el.scrollTop = el.scrollHeight
  }, [active, nodeLogLines])

  useLayoutEffect(() => {
    if (active !== 'start' || buildLogLines.length === 0) return
    const el = buildLogScroller.current
    if (!el) return
    el.scrollTop = el.scrollHeight
  }, [active, buildLogLines])

  const installBusy =
    portsConfirming ||
    diskSaving ||
    clientsSyncing ||
    (running &&
      (active === 'snapshot' ||
        active === 'start' ||
        active === 'sync' ||
        active === 'ports' ||
        active === 'disks' ||
        active === 'clients'))
  // Never flash the wizard-wide loader during/after Confirm ports.
  const stepPending =
    active == null &&
    !portsConfirming &&
    !(
      agentAckedStep.current &&
      agentAckedStep.current !== 'ports' &&
      agentAckedStep.current !== 'disks'
    )

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
    // Logs modal is opt-in (Show logs). Never pop it on Install / retry / resume.
    setInstallModalOpen(false)
  }

  function markInstallOk() {
    setInstallOutcome((prev) => (prev === 'fail' ? prev : 'ok'))
  }

  // A toast for a failed install is gone in seconds and the operator is left with a
  // stalled wizard and no reason. Force the modal open and let them close it.
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
        network: workload.network || '',
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

  // Fixed ports from Kotlin catalog; live bind status is probed on the Check ports step.
  const mapNodePortsResponse = useCallback(
    (res: Awaited<ReturnType<typeof api.nodePorts>>) =>
      (res.items || []).map((it) => ({
        port: it.port,
        role: it.role,
        label: it.label,
        config: it.config,
        config_enabled_default: it.config_enabled_default,
        external: false,
        bind: it.free == null ? undefined : it.free ? 'free' : 'busy',
        holder: it.holder || undefined,
      })),
    [],
  )

  const checkHostPortsLive = useCallback(
    async () => {
      if (!workload?.server_id || !workload?.network || !workload?.env) return
      setNodePortsChecking(true)
      try {
        const res = await api.checkHostPorts({
          server_id: workload.server_id,
          network: workload.network,
          env: workload.env,
        })
        setNodePortsCatalog(mapNodePortsResponse(res))
        if (res.ok === false) {
          setPortsError(res.message || res.error || 'Could not check ports on the host')
        } else {
          setPortsError(null)
        }
      } catch (e) {
        setPortsError(String((e as Error).message || e))
      } finally {
        setNodePortsChecking(false)
      }
    },
    [workload?.server_id, workload?.network, workload?.env, mapNodePortsResponse],
  )

  useEffect(() => {
    if (active !== 'ports') {
      nodePortsLiveFetched.current = false
    }
  }, [active])

  useEffect(() => {
    const id = workload?.id
    if (!id) {
      setNodePortsCatalog([])
      return
    }
    if (stepPending || active !== 'ports' || portsConfirming) return
    if (nodePortsLiveFetched.current) return
    nodePortsLiveFetched.current = true
    void checkHostPortsLive()
  }, [active, stepPending, portsConfirming, workload?.server_id, workload?.network, workload?.env, checkHostPortsLive])

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
      nodePortsLiveFetched.current = false
      setUnsupported(null)
      void askAgentPorts(true)
      if (workload?.server_id) void checkHostPortsLive()
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
  // Also re-seed agentAckedStep after refresh (refs are empty on remount).
  useEffect(() => {
    if (!active) return
    if (uiStep == null) setUiStep(active)
    if (
      (active === 'snapshot' ||
        active === 'start' ||
        active === 'node_type' ||
        active === 'clients') &&
      !agentAckedStep.current &&
      !manualBackToPorts.current &&
      !manualBackToDisks.current &&
      !manualBackToNodeType.current &&
      !manualBackToClients.current
    ) {
      agentAckedStep.current = active
    }
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
      network: workload.network || '',
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
        network: workload.network || '',
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
      if (usingLiveNodeCatalog && workload?.id) {
        nodePortsLiveFetched.current = false
        await checkHostPortsLive()
      } else {
        await refreshPortCheck()
      }
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
        network: workload.network || '',
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
    if (stepPending || (active !== 'ports' && active !== 'disks' && active !== 'start')) return
    if (!workload?.server_id) return
    void loadHostDisks()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [networkId, active, stepPending, workload?.server_id, env])

  useEffect(() => {
    if (stepPending || active !== 'start') return
    if (!isSolanaNetwork(networkId) || !workload?.server_id) return
    void loadHostSysctl()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [networkId, active, stepPending, workload?.server_id])

  function goToDisksStep() {
    manualBackToPorts.current = false
    manualBackToDisks.current = false
    manualBackToNodeType.current = false
    setPortsError(null)
    setUiStep('disks')
    agentAckedStep.current = 'disks'
  }

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
    const l1 = l1ParentFromNetworksCatalog(networkId, env)
    if (l1?.rpc && !(next.l1_rpc || '').trim()) next.l1_rpc = l1.rpc
    if (l1?.beacon && !(next.l1_beacon || '').trim()) next.l1_beacon = l1.beacon
    setInstallOptions(next)
  }, [networkId, env, workload?.id, networksCatalog])

  async function loadHostDisks() {
    if (!workload?.server_id || !networkId) return
    setDiskLoading(true)
    setDiskError(null)
    try {
      const res = await api.workloadsHostDisks({
        server_id: workload.server_id,
      })
      if (!res.ok && !(res.disks?.length || res.mounts?.length || res.unused?.length)) {
        throw new Error(res.message || res.error || 'host disks failed')
      }
      const mountsNow = res.mounts || []
      const disksNow = res.disks || []
      const unusedNow =
        (res.unused || []).length > 0 ? res.unused || [] : unusedFromInventory(disksNow, mountsNow)
      setDiskMounts(mountsNow)
      setDiskRows(disksNow)
      setDiskUnused(unusedNow)
      if (res.error || (!disksNow.length && !unusedNow.length && !mountsNow.length)) {
        setDiskError(res.error || res.message || null)
      }
      setDiskInsights(res.insights || [])
      setDiskNofile(null)
      setDiskSummary(res.summary || '')

      let rolesCatalog: typeof diskRoles = []
      let layoutRules: string[] = []
      let recommended: MultiDiskLayoutPlan | null = null
      let saved: MultiDiskLayoutPlan | null = null
      let nextClientConfig: ClientConfigSpec | null = null
      if (workload.id) {
        try {
          const dl = await api.workloadsDiskLayout(workload.id)
          rolesCatalog = dl.multi_disk_roles || []
          layoutRules = dl.layout_rules || []
          recommended = dl.recommended || null
          nextClientConfig = dl.client_config || null
          if (dl.install_options && typeof dl.install_options === 'object') {
            setInstallOptions((prev) => ({ ...prev, ...dl.install_options }))
          }
          void loadProgramCatalogPorts(workload.id)
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
            saved = normalizeDiskLayoutRoles(
              {
                ...(raw as MultiDiskLayoutPlan),
                roles: rolesArr.length ? rolesArr : (raw as MultiDiskLayoutPlan).roles,
              },
              rolesCatalog,
              networkId,
              env,
            )
          }
        } catch {
          /* host tip disks still usable; roles / clientConfig fall back below */
        }
      }
      if (rolesCatalog.length === 0) {
        rolesCatalog = diskRolesFromNetworksCatalog(networkId, env)
      }
      if (!nextClientConfig?.bindings?.length) {
        nextClientConfig = clientConfigFromNetworksCatalog(networkId)
      }
      if (rolesCatalog.length === 0 || !nextClientConfig?.bindings?.length) {
        try {
          const all = await api.networksAll()
          const hit = (all.items || []).find((n) => n.id === networkId)
          if (rolesCatalog.length === 0) {
            const roles = hit?.disk_roles || []
            if (roles.length) {
              const envDetail = (hit?.env_details || []).find((e) => e.id === env)
              const hint = envDetail?.full_node_gib || envDetail?.disk_hint_gib
              rolesCatalog = roles.map((r) => ({
                id: r.id,
                label: r.label,
                leaf: r.id,
                size_hint_gib: hint,
              }))
              if (!layoutRules.length && hit?.disk_notes?.length) {
                layoutRules = hit.disk_notes
              }
            }
          }
          if (!nextClientConfig?.bindings?.length && hit?.client_config?.bindings?.length) {
            nextClientConfig = hit.client_config
          }
        } catch {
          /* keep empty — panel may need restart for new network.yml */
        }
      }
      if (nextClientConfig) {
        setClientConfig(nextClientConfig)
        seedClientConfigOptions(nextClientConfig)
      }
      setDiskRules(layoutRules)
      setDiskSnapshotHint(null)
      setDiskRoles(rolesCatalog)
      const rec = usableDiskLayout(recommended, unusedNow, mountsNow)
      const normalizedRec =
        rec && rolesCatalog.length
          ? normalizeDiskLayoutRoles(rec, rolesCatalog, networkId, env) || rec
          : rec
      setDiskRecommended(normalizedRec)
      setDiskLayout(usableDiskLayout(saved, unusedNow, mountsNow) || normalizedRec)
    } catch (e) {
      setDiskError(String((e as Error).message || e))
    } finally {
      setDiskLoading(false)
    }
  }

  async function loadHostSysctl() {
    if (!workload?.server_id) return
    setHostSysctlError(null)
    try {
      const res = await api.workloadsHostSysctl({ server_id: workload.server_id })
      if (res.ok === false || !res.recommended) {
        setHostSysctl(null)
        setHostSysctlError(res.message || res.error || 'host sysctl unavailable')
        return
      }
      const recommended = res.recommended || {}
      const current = res.current || {}
      const optionByKey = res.install_option_keys || {}
      setHostSysctl({ current, recommended, optionByKey })
      setInstallOptions((prev) => {
        const next = { ...prev }
        let changed = false
        for (const [key, opt] of Object.entries(optionByKey)) {
          if (!opt) continue
          if (next[opt] != null && String(next[opt]).trim() !== '') continue
          const rec = recommended[key]
          if (typeof rec === 'number' && rec > 0) {
            next[opt] = String(rec)
            changed = true
          }
        }
        return changed ? next : prev
      })
    } catch (e) {
      setHostSysctl(null)
      setHostSysctlError(String((e as Error).message || e))
    }
  }

  function applyRecommendedSysctl() {
    if (!hostSysctl) return
    setInstallOptions((prev) => {
      const next = { ...prev }
      for (const [key, opt] of Object.entries(hostSysctl.optionByKey)) {
        if (!opt) continue
        const rec = hostSysctl.recommended[key]
        if (typeof rec === 'number' && rec > 0) next[opt] = String(rec)
      }
      return next
    })
  }

  function diskLayoutPayload(layout: MultiDiskLayoutPlan) {
    const normalized =
      normalizeDiskLayoutRoles(layout, diskRoles, networkId, env) || layout
    const rolesMap =
      normalized.roles_map ||
      Object.fromEntries(
        (normalized.roles || [])
          .filter((r) => r.id)
          .map((r) => [r.id, { dir: r.dir, mount: r.mount }]),
      )
    return {
      ledger_dir: normalized.ledger_dir,
      accounts_dir: normalized.accounts_dir,
      snapshots_dir: normalized.snapshots_dir,
      disk_layout: {
        strategy: normalized.strategy,
        ledger_mount: normalized.ledger_mount,
        accounts_mount: normalized.accounts_mount,
        snapshots_mount: normalized.snapshots_mount,
        ledger_dir: normalized.ledger_dir,
        accounts_dir: normalized.accounts_dir,
        snapshots_dir: normalized.snapshots_dir,
        state_dir: normalized.state_dir,
        index_dir: normalized.index_dir,
        state_mount: normalized.state_mount,
        index_mount: normalized.index_mount,
        roles: rolesMap,
      },
    }
  }

  // The operator's disk choice must outlive this render: a failed Install, a page
  // reload or a re-opened wizard used to come back with the tip recommendation and
  // silently move the datadir. Persist on every change, not only on provision.
  useEffect(
    () => () => {
      if (diskSaveTimer.current) clearTimeout(diskSaveTimer.current)
    },
    [],
  )

  function applyDiskLayout(next: MultiDiskLayoutPlan) {
    const normalized =
      normalizeDiskLayoutRoles(next, diskRoles, networkId, env) || next
    setDiskLayout(normalized)
    if (!workload?.id) return
    const id = workload.id
    const doc = diskLayoutPayload(normalized).disk_layout
    if (diskSaveTimer.current) clearTimeout(diskSaveTimer.current)
    diskSaveTimer.current = setTimeout(() => {
      api
        .workloadsSaveDiskLayout(id, doc)
        .then((res) => {
          setDiskSaved(res.ok !== false)
          if (res.ok !== false) void onWorkloadUpdated?.()
        })
        .catch(() => setDiskSaved(false))
    }, 400)
  }

  async function saveDiskLayoutNow(): Promise<boolean> {
    if (!workload?.id) return true
    if (!wantsDiskLayout) return true
    const layout = diskLayout || diskRecommended
    if (!layout) {
      setDiskError('Pick a disk layout before continuing')
      return false
    }
    if (diskSaveTimer.current) {
      clearTimeout(diskSaveTimer.current)
      diskSaveTimer.current = null
    }
    setDiskSaving(true)
    setDiskError(null)
    try {
      const doc = diskLayoutPayload(layout).disk_layout
      const res = await api.workloadsSaveDiskLayout(workload.id, doc)
      if (res.ok === false) {
        throw new Error(res.message || res.error || 'save disk layout failed')
      }
      setDiskSaved(true)
      void onWorkloadUpdated?.()
      return true
    } catch (e) {
      setDiskSaved(false)
      setDiskError(String((e as Error).message || e))
      return false
    } finally {
      setDiskSaving(false)
    }
  }

  async function continueFromDisks() {
    if (portsConfirming || diskSaving) return
    manualBackToPorts.current = false
    manualBackToDisks.current = false
    manualBackToNodeType.current = false
    manualBackToClients.current = false
    setPortsError(null)
    setError(null)
    const saved = await saveDiskLayoutNow()
    if (!saved) return
    if (wantsNodeTypeStep) {
      setUiStep('node_type')
      agentAckedStep.current = 'node_type'
      return
    }
    await enterClientsStep()
  }

  async function continueFromNodeType() {
    if (!workload?.id || !wantsNodeTypeStep) return
    manualBackToPorts.current = false
    manualBackToDisks.current = false
    manualBackToNodeType.current = false
    manualBackToClients.current = false
    setError(null)
    try {
      const payload: Record<string, string> = {}
      for (const g of nodeTypeOptionGroups) {
        const v = (installOptions[g.id] || g.default || g.choices[0]?.id || '').trim()
        if (v) payload[g.id] = v
      }
      if (wantsXrplHistory && !payload.xrpl_history) {
        payload.xrpl_history = installOptions.xrpl_history || xrplHistory
      }
      if (Object.keys(payload).length) {
        await api.workloadsSaveInstallOptions(workload.id, { install_options: payload })
        void onWorkloadUpdated?.()
      }
    } catch (e) {
      setError(String((e as Error).message || e))
      return
    }
    await enterClientsStep()
  }

  async function enterClientsStep() {
    clientsAutoStarted.current = false
    setClientsSynced(false)
    setClientsError(null)
    setClientsFiles([])
    setClientsPath(null)
    await setWlStatus('needs_clients')
    setUiStep('clients')
    agentAckedStep.current = 'clients'
  }

  async function syncClientsToHost(): Promise<boolean> {
    if (!workload?.id || clientsSyncing) return false
    setClientsSyncing(true)
    setClientsError(null)
    try {
      const res = await api.workloadsApplyClientConfig(workload.id, {
        install_options: installOptions,
      })
      if (res.ok === false) {
        const err = (res.error || '').toLowerCase()
        if (err === 'no_client_config') {
          setClientsSynced(true)
          setClientsFiles([])
          setClientsPath(res.path || null)
          setClientsError(null)
          notifications.show({
            color: 'gray',
            message: 'No client binaries for this network — continue',
          })
          return true
        }
        throw new Error(res.message || res.error || 'Could not sync clients to host')
      }
      setClientsSynced(true)
      setClientsFiles(res.files || [])
      setClientsPath(res.path || null)
      notifications.show({
        color: 'teal',
        message:
          (res.files?.length || 0) > 0
            ? `Client synced to host (${res.files!.length} file${res.files!.length === 1 ? '' : 's'})`
            : 'Client synced to host',
      })
      void onWorkloadUpdated?.()
      return true
    } catch (e) {
      const msg = String((e as Error).message || e)
      setClientsSynced(false)
      setClientsError(msg)
      await setWlStatus('clients_error')
      return false
    } finally {
      setClientsSyncing(false)
    }
  }

  async function continueFromClients() {
    if (!workload?.id || clientsSyncing) return
    if (!clientsSynced) {
      const ok = await syncClientsToHost()
      if (!ok) return
    }
    manualBackToClients.current = false
    manualBackToDisks.current = false
    manualBackToNodeType.current = false
    if (allowSnap) {
      await setWlStatus('needs_snapshot')
      setUiStep('snapshot')
      agentAckedStep.current = 'snapshot'
      void loadSnapshotPlan()
      return
    }
    setUiStep('start')
    agentAckedStep.current = 'start'
    void loadProgramCatalogPorts()
  }

  function goBackToClientsOrEarlier() {
    manualBackToPorts.current = false
    manualBackToDisks.current = false
    manualBackToNodeType.current = false
    manualBackToClients.current = true
    agentAckedStep.current = 'clients'
    setUiStep('clients')
  }

  function goBackToNodeTypeOrDisks() {
    manualBackToPorts.current = false
    manualBackToClients.current = false
    if (wantsNodeTypeStep) {
      manualBackToDisks.current = false
      manualBackToNodeType.current = true
      agentAckedStep.current = 'node_type'
      setUiStep('node_type')
      return
    }
    manualBackToNodeType.current = false
    manualBackToDisks.current = true
    agentAckedStep.current = 'disks'
    setUiStep('disks')
  }

  // Auto-start client sync when entering the Clients step.
  useEffect(() => {
    if (active !== 'clients' || !workload?.id) return
    if (clientsSynced || clientsSyncing || clientsAutoStarted.current) return
    clientsAutoStarted.current = true
    void syncClientsToHost()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- sync once per enter; Retry calls syncClientsToHost directly
  }, [active, workload?.id, clientsSynced, clientsSyncing])

  async function loadSnapshotPlan() {
    if (!workload?.id) return
    setSnapshotPlanLoading(true)
    setSnapshotPlanError(null)
    try {
      const res = await api.nodeSnapshotPlan(workload.id)
      if (res.ok === false) {
        throw new Error(res.message || res.error || 'Could not load snapshot plan')
      }
      setSnapshotPlan(res)
      const defaultSource =
        res.default_source_id ||
        res.source ||
        res.sources?.find((s) => s.available)?.id ||
        'official'
      setSnapshotSourceId(defaultSource)
      const types = res.snapshot_types || []
      if (types.length > 0) {
        const group = {
          id: 'snapshot',
          label: 'Node / snapshot type',
          hint: 'Each type uses a different official mirror; CDN stores archives under /snapshots/{network}/{env}/{type}/.',
          default: types.find((t) => t.default)?.id || types[0]?.id,
          choices: types.map((t) => ({
            id: t.id,
            title: t.label || t.id,
            hint: t.hint,
          })),
        }
        setInstallGroups((prev) => {
          const keep = prev.filter((g) => g.id !== 'snapshot')
          return [...keep, group]
        })
        setInstallOptions((prev) => {
          const snapshot =
            prev.snapshot ||
            res.type_id ||
            workload.install_options?.snapshot ||
            group.default ||
            types[0]?.id ||
            ''
          if (snapshot && !String(workload.install_options?.snapshot || '').trim()) {
            void api
              .workloadsSaveInstallOptions(workload.id, { snapshot })
              .then(() => onWorkloadUpdated?.())
              .catch(() => undefined)
          }
          return { ...prev, snapshot }
        })
      }
      void loadSnapshotSpeedProbe(
        (
          res.type_id ||
          types.find((t) => t.default)?.id ||
          types[0]?.id ||
          installOptions.snapshot ||
          ''
        ).trim(),
        res.via_node ? [] : (res.sources || []).map((s) => s.id),
      )
    } catch (e) {
      setSnapshotPlan(null)
      setSnapshotPlanError(String((e as Error).message || e))
    } finally {
      setSnapshotPlanLoading(false)
    }
  }

  async function refreshSnapshotProgress() {
    if (!workload?.id) return
    try {
      const res = await api.nodeSnapshotProgress(workload.id)
      setSnapshotProgress(res)
      if (res.ready) {
        await onWorkloadUpdated?.()
      }
      if (res.failed) {
        setError(res.error || res.detail || 'Snapshot download failed')
      }
    } catch {
      /* ignore transient poll errors */
    }
  }

  function stopSnapshotPolling() {
    if (snapshotPollTimer.current) {
      clearInterval(snapshotPollTimer.current)
      snapshotPollTimer.current = null
    }
  }

  function startSnapshotPolling() {
    stopSnapshotPolling()
    void refreshSnapshotProgress()
    snapshotPollTimer.current = setInterval(() => {
      void refreshSnapshotProgress()
    }, 2000)
  }

  async function loadSnapshotSpeedProbe(typeId?: string, sourceIds?: string[]) {
    if (!workload?.id) return
    const snapshot = (typeId || installOptions.snapshot || '').trim()
    const ids = sourceIds || snapshotPlan?.sources?.map((s) => s.id) || []
    if (ids.length === 0) {
      setSnapshotSpeedById({})
      return
    }
    setSnapshotSpeedById((prev) => {
      const next = { ...prev }
      for (const id of ids) {
        next[id] = { ...next[id], loading: true, error: undefined }
      }
      return next
    })
    try {
      const res = await api.nodeSnapshotProbe(workload.id, {
        snapshot: snapshot || undefined,
      })
      if (res.ok === false) {
        throw new Error(res.message || res.error || 'Could not probe snapshot speed')
      }
      const next: Record<string, SnapshotSpeedReading> = {}
      for (const row of res.results || []) {
        next[row.id] = {
          loading: false,
          available: row.available,
          bytes_per_sec: row.bytes_per_sec,
          detail: row.detail,
        }
      }
      setSnapshotSpeedById(next)
    } catch (e) {
      const msg = String((e as Error).message || e)
      const next: Record<string, SnapshotSpeedReading> = {}
      for (const id of ids) {
        next[id] = { loading: false, error: msg }
      }
      setSnapshotSpeedById(next)
    }
  }

  async function persistSnapshotType(typeId: string): Promise<boolean> {
    const id = (typeId || '').trim()
    if (!workload?.id || !id) return false
    try {
      const saved = await api.workloadsSaveInstallOptions(workload.id, { snapshot: id })
      if (saved.ok === false) {
        throw new Error(saved.message || saved.error || 'Could not save snapshot type')
      }
      await onWorkloadUpdated?.()
      await loadSnapshotPlan()
      void loadSnapshotSpeedProbe(id)
      return true
    } catch (e) {
      setSnapshotPlanError(String((e as Error).message || e))
      return false
    }
  }

  async function startPanelSnapshotDownload() {
    if (!workload?.id || snapshotStarting) return
    setSnapshotStarting(true)
    setError(null)
    setSnapshotIdleAfterStop(false)
    try {
      if (snapshotViaNode) {
        pushLog('Via-node snapshot — continue to Start; Agave downloads the cluster archive')
        await continueFromSnapshot()
        return
      }
      const typeId = (installOptions.snapshot || '').trim()
      if (!typeId) {
        throw new Error('Pick a Node / snapshot type before download')
      }
      if (!snapshotSourceId) {
        throw new Error('Pick a snapshot download source')
      }
      const res = await api.nodeSnapshotStart(workload.id, {
        snapshot: typeId,
        source: snapshotSourceId,
      })
      if (res.ok === false) {
        throw new Error(res.message || res.error || 'Could not start snapshot download')
      }
      if (res.url || res.dest_dir || res.type_id) {
        pushLog(
          `Snapshot start type=${res.type_id || typeId}` +
            (res.url ? ` url=${res.url}` : '') +
            (res.dest_dir ? ` dest=${res.dest_dir}` : ''),
        )
      }
      await onWorkloadUpdated?.()
      await loadSnapshotPlan()
      await setWlStatus('snapshot_running')
      startSnapshotPolling()
      void refreshSnapshotProgress()
    } catch (e) {
      setError(String((e as Error).message || e))
    } finally {
      setSnapshotStarting(false)
    }
  }

  async function continueFromSnapshot() {
    setError(null)
    setUiStep('start')
    agentAckedStep.current = 'start'
    stopSnapshotPolling()
    void loadProgramCatalogPorts()
  }

  useEffect(() => {
    if (active !== 'start') return
    void loadProgramCatalogPorts()
    if (clientConfig) seedClientConfigOptions(clientConfig)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, workload?.id])

  async function loadProgramCatalogPorts(nodeId?: string) {
    const id = nodeId || workload?.id
    if (!id) return
    try {
      const res = await api.nodePorts(id)
      const items = (res.items || [])
        .filter((it) => it.role && Number(it.port) > 0)
        .map((it) => ({
          port: Number(it.port),
          role: String(it.role),
          label: it.label || String(it.role),
          config: it.config,
          config_enabled_default: it.config_enabled_default,
        }))
      setProgramCatalogPorts(items)
      seedClientConfigOptions(clientConfig, items)
    } catch {
      /* keep previous catalog */
    }
  }

  function seedClientConfigOptions(
    cfg: ClientConfigSpec | null | undefined,
    ports: CatalogPortPolicy[] = programCatalogPorts,
  ) {
    if (!cfg?.bindings?.length && ports.length === 0) return
    setInstallOptions((prev) => {
      const next = { ...prev }
      let changed = false
      for (const p of ports) {
        if (catalogPortConfigPolicy(p) !== 'optional') continue
        const opt = portConfigInstallOptionKey(String(p.role || ''))
        if (!opt || next[opt] != null) continue
        next[opt] = '0'
        changed = true
      }
      for (const b of cfg?.bindings || []) {
        const whenOpt = (b.when_install_option || '').trim()
        if (whenOpt) {
          if (next[whenOpt] == null || next[whenOpt] === '') {
            next[whenOpt] = '0'
            changed = true
          }
          continue
        }
        if ((b.source || '').toLowerCase() !== 'install_option') continue
        const opt = (b.option || '').trim()
        if (!opt) continue
        if (next[opt] == null || next[opt] === '') {
          const def = (b.default || '').trim()
          if (def) {
            next[opt] = def
            changed = true
          }
        }
      }
      return changed ? next : prev
    })
  }

  async function testConnectConfig(kind: string, url: string, optionKey: string) {
    const endpoint = (url || '').trim()
    if (!endpoint) {
      setTestConnectResult((prev) => ({
        ...prev,
        [optionKey]: { ok: false, detail: 'URL is empty' },
      }))
      return
    }
    setTestConnectBusy(optionKey)
    try {
      const res = await api.networksTestConnect({ kind, url: endpoint })
      if (res.ok === false) {
        setTestConnectResult((prev) => ({
          ...prev,
          [optionKey]: {
            ok: false,
            detail: res.message || res.error || 'connect failed',
          },
        }))
        return
      }
      setTestConnectResult((prev) => ({
        ...prev,
        [optionKey]: { ok: true, detail: res.detail || 'ok' },
      }))
    } catch (e) {
      setTestConnectResult((prev) => ({
        ...prev,
        [optionKey]: { ok: false, detail: String((e as Error).message || e) },
      }))
    } finally {
      setTestConnectBusy(null)
    }
  }

  async function continueFromStart() {
    if (!workload?.id || startSaving) return
    setStartSaving(true)
    setStartApplyError(null)
    setStartBuildPending(null)
    try {
      // Launch only — client files were synced on the Clients step after Disks.
      const res = await api.workloadsStartNode(workload.id, {
        install_options: installOptions,
      })
      if (res.ok === false) {
        if (
          (res.error || '').toLowerCase() === 'client_build_pending' ||
          /build (started|still running)/i.test(res.message || '')
        ) {
          setStartBuildPending(res.message || res.error || 'Client build in progress on the host')
          notifications.show({
            color: 'yellow',
            title: 'Building client on host',
            message: 'Press Start again when the binary is ready (see alert).',
            autoClose: 8000,
          })
          return
        }
        throw new Error(res.message || res.error || 'start node failed')
      }
      setStartBuildPending(null)
      await onWorkloadUpdated?.()
      notifications.show({
        color: 'teal',
        message: res.already_running
          ? `Node already running (pid ${res.pid ?? '?'}) — status sync`
          : `Node started (pid ${res.pid ?? '?'}) — status sync`,
      })
      setUiStep('sync')
      agentAckedStep.current = 'sync'
    } catch (e) {
      const msg = String((e as Error).message || e)
      if (/build (started|still running)|client_build_pending/i.test(msg)) {
        setStartBuildPending(msg)
        notifications.show({
          color: 'yellow',
          title: 'Building client on host',
          message: 'Press Start again when the binary is ready.',
          autoClose: 8000,
        })
      } else {
        setStartApplyError(msg)
      }
    } finally {
      setStartSaving(false)
    }
  }

  useEffect(() => {
    if (active !== 'snapshot') {
      stopSnapshotPolling()
      return
    }
    void loadSnapshotPlan()
    if (workload?.status === 'snapshot_running') {
      startSnapshotPolling()
    }
    return () => stopSnapshotPolling()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, workload?.id])

  useEffect(() => () => stopSnapshotPolling(), [])

  /** Legacy tip flow: check ports → provision → snapshot/start. */
  async function installWithPortCheck() {
    if (portsConfirming) return
    manualBackToPorts.current = false
    if (!workload?.id) {
      const msg = 'Node id missing — reload the page'
      setPortsError(msg)
      markInstallFail(msg)
      return
    }
    const installPorts = resolveInstallPorts(
      usingLiveNodeCatalog,
      nodePortsCatalog,
      ports,
      workload,
    )
    if (!workload.server_id || !installPorts?.public_port) {
      const msg = usingLiveNodeCatalog
        ? 'Catalog ports are not ready — re-check ports on the previous step'
        : 'Catalog ports missing — re-add the node so tip can return ports'
      setPortsError(msg)
      markInstallFail(msg)
      return
    }
    setPortsConfirming(true)
    setPortsConfirmCountdown(0)
    setPortsError(null)
    setUiStep(allowSnap ? 'snapshot' : 'start')
    let advanced = false
    try {
      pushLog('Check ports: tip catalog…')
      const check = await api.workloadsCheckPorts({
        server_id: workload.server_id,
        network: workload.network || '',
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
      pushLog('Host: download client, write units, start leaf agents (can take several minutes)…')

      const layout = wantsDiskLayout ? diskLayout || diskRecommended : null
      if (wantsDiskLayout && layout) {
        const roleSummary = (layout.roles || [])
          .map((r) => `${r.id}=${r.dir}`)
          .join(' ')
        pushLog(`Disk layout: ${layout.strategy || '?'} ${roleSummary || layout.ledger_dir || ''}`.trim())
      } else if (wantsDiskLayout) {
        // Say it out loud: without a plan the tip places the roles itself, and
        // the operator must not learn that from a preflight that measured /.
        pushLog('Disk layout: not picked here — tip places roles on the host data disks')
      }
      if (wantsInstallOptions) {
        pushLog(`Install options: ${installOptionLabel(installGroups, installOptions)}`)
      }
      const res = await api.workloadsProvision({
        server_id: workload.server_id,
        network: workload.network || '',
        env,
        name: workload.name,
        public_port: installPorts.public_port,
        agent_port: installPorts.agent_port,
        node_http_port: installPorts.node_http_port,
        p2p_port: installPorts.p2p_port,
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
          public_port: res.item.public_port || installPorts.public_port,
          agent_port: res.item.agent_port || installPorts.agent_port,
          node_http_port: res.item.node_http_port || installPorts.node_http_port,
          p2p_port: res.item.p2p_port || installPorts.p2p_port,
          source: 'agent',
        })
      }
      pushLog('Agent ACK: provision ok — waiting 10s for the per-node units to come up…')
      for (let n = 10; n >= 1; n--) {
        setPortsConfirmCountdown(n)
        await sleep(1000)
      }
      setPortsConfirmCountdown(0)
      pushLog('Polling the node agent for lifecycle status…')
      void onRefresh()

      const leafUnit = `rpcnode-api-agent-${networkId}-${env}`
      const acked = await waitPanelLifecycleAck(agentTarget, 'ports', {
        timeoutMs: 75_000,
        // The leaf agent is started by this very provision: systemd may need
        // minutes (and a few Restart=always cycles) before its port answers.
        dialGraceMs: 300_000,
        acceptCurrent: ['install', 'snapshot', 'start', 'ibd', 'run'],
        onTick: (st) => {
          const d = st.lifecycle?.detail || st.lifecycle?.steps?.find((s) => s.id === 'ports')?.detail
          if (d) pushLog(`ports: ${d}`)
        },
        onWaiting: (detail, waited) => {
          pushLog(
            `Node agent not answering yet (${Math.round(waited / 1000)}s) — ${leafUnit} is starting. ${detail}`,
          )
        },
        notListening: (detail, waited) =>
          `Node agent ${networkId}/${env} never answered on port ${
            installPorts.agent_port || '?'
          } within ${Math.round(waited / 1000)}s. Its unit ${leafUnit} is not staying up — open its log (Debug → unit) before pressing Restart. Last dial: ${detail}`,
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
        advanced = true
        await onWorkloadUpdated?.()
        void onRefresh()
        return
      }
      const next = wizardStepFromAgentLifecycle(acked, allowSnap)
      const portsStep = acked.lifecycle?.steps?.find((s) => (s.id || '') === 'ports')
      const mappedNext =
        next && next !== 'ports' && next !== 'install' ? next : null
      const leaveTo: WizardStepId =
        mappedNext && !acked.needs_provision
          ? mappedNext
          : allowSnap
            ? 'snapshot'
            : 'start'
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
      if (/insufficient disk|snapshot/i.test(msg)) {
        setError(msg)
        setUiStep('snapshot')
        agentAckedStep.current = 'snapshot'
        void setWlStatus('snapshot_error')
      } else if (/port_busy|ports busy|port.*busy|reach|filtered/i.test(msg)) {
        setPortsError(msg)
        manualBackToPorts.current = true
        setUiStep('ports')
      } else if (/disk|layout|nofile|limits|data nvme/i.test(msg)) {
        setError(msg)
        setUiStep('disks')
      } else {
        setPortsError(msg)
        setUiStep('disks')
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
      // Refresh local workload.status — derived prefers panel status over uiStep/agentAckedStep.
      // Without this, Continue Clients → needs_snapshot leaves status as needs_clients and the
      // rail sticks on Clients until something else reloads (e.g. Retry sync).
      await onWorkloadUpdated?.()
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
        pushLog('Host: download client and write units (can take several minutes)…')
        if (!workload?.server_id) {
          throw new Error('No server linked — cannot install')
        }
        const layout = wantsDiskLayout ? diskLayout || diskRecommended : null
        const prov = await api.workloadsProvision({
          server_id: workload.server_id,
          network: workload.network || '',
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
        await waitPanelLifecycleAck(agentTarget, 'install', {
          timeoutMs: 90_000,
          // A client download / unit rewrite bounces the leaf agent mid-install.
          dialGraceMs: 180_000,
          acceptCurrent: ['start', 'ibd', 'run'],
          onWaiting: (detail, waited) =>
            pushLog(`install: node agent restarting (${Math.round(waited / 1000)}s) — ${detail}`),
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
        if ((started.action || started.agent?.action) === 'snapshot') {
          pushLog('Start deferred — official snapshot running')
          agentAckedStep.current = 'snapshot'
          setUiStep('snapshot')
          await setWlStatus('snapshot_running')
          await onRefresh()
          await onWorkloadUpdated?.()
          return
        }
        pushLog('Agent API ACK: start ok — waiting lifecycle…')
        const afterStart = await waitPanelLifecycleAck(agentTarget, 'start', {
          timeoutMs: 90_000,
          dialGraceMs: 180_000,
          acceptCurrent: ['ibd', 'run', 'healthy'],
          onWaiting: (detail, waited) =>
            pushLog(`start: node agent restarting (${Math.round(waited / 1000)}s) — ${detail}`),
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
        await requestSnapshotStart()
        const snapProg = await api.nodeSnapshotProgress(workload!.id)
        if (snapProg?.failed) {
          throw new Error(snapProg.error || snapProg.detail || 'Snapshot start failed')
        }
        pushLog('Panel ACK: snapshot start — waiting lifecycle…')
        const acked = await waitPanelLifecycleAck(agentTarget, 'snapshot', {
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
          const acked = await waitPanelLifecycleAck(
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
          failLane(
            classifySetupError(ackMsg, {
              allowSnap,
              snapReady: snapReady(status),
              hint: allowSnap ? (snapReady(status) ? 'start' : 'snapshot') : 'install',
            }),
            ackMsg,
          )
          return
        }
      }
      failLane(
        classifySetupError(msg, {
          allowSnap,
          snapReady: snapReady(status),
          hint: allowSnap ? (snapReady(status) ? 'start' : 'snapshot') : 'install',
        }),
        msg,
      )
      await onWorkloadUpdated?.()
    }
  }

  function failLane(id: SetupLaneId, msg: string) {
    const lane = wizardStepFromFailedLane(id)
    const uiLane = wizardVisibleStep(lane, allowSnap) || lane
    if (lane === 'ports') {
      setPortsError(msg)
      setError(null)
    } else {
      setError(msg)
      setPortsError(null)
    }
    setRunning(false)
    setUiStep(uiLane)
    agentAckedStep.current = uiLane
    pushLog(`ERROR [${id}]: ${msg}`)
    markInstallFail(msg)
    if (id === 'snapshot') void setWlStatus('snapshot_error')
    if (id === 'start') void setWlStatus('start_error')
  }

  async function requestSnapshotStart() {
    if (snapshotStartsViaNode(status, networkId)) {
      await api.workloadsStart({
        workload_id: workload?.id,
        server_id: workload?.server_id,
        env,
      })
      return
    }
    await api.snapshotStart(agentTarget)
  }

  async function retrySnapshot() {
    setError(null)
    setSnapshotIdleAfterStop(false)
    setRunning(true)
    setUiStep('snapshot')
    agentAckedStep.current = 'snapshot'
    startInstallWatch()
    pushLog('Retry snapshot — archives kept')
    try {
      await requestSnapshotStart()
      const acked = await waitPanelLifecycleAck(agentTarget, 'snapshot', {
        timeoutMs: 60_000,
        acceptCurrent: snapReady(status) ? ['start', 'run'] : [],
      })
      agentAckedStep.current = wizardStepFromAgentLifecycle(acked, allowSnap) || 'snapshot'
      setUiStep(agentAckedStep.current)
      await setWlStatus('snapshot_running')
      await onRefresh()
      await onWorkloadUpdated?.()
    } catch (e) {
      failLane('snapshot', String((e as Error).message || e))
      await onWorkloadUpdated?.()
    }
  }

  async function confirmStopSnapshot() {
    setStoppingSnapshot(true)
    setError(null)
    pushLog('Stop snapshot requested')
    try {
      if (workload?.id) {
        const res = await api.nodeSnapshotStop(workload.id)
        if (res.ok === false) {
          throw new Error(res.message || res.error || 'stop failed')
        }
      } else {
        const res = await api.snapshotStop(agentTarget)
        if (!res.ok) {
          throw new Error(res.error || 'stop failed')
        }
        await waitPanelSnapshotStopped(agentTarget, {
          onTick: () => {
            void onRefresh()
          },
        })
      }
      stopSnapshotPolling()
      setSnapshotProgress(null)
      setRunning(false)
      setError(null)
      setSnapshotIdleAfterStop(true)
      pushLog('Snapshot stopped — partial files removed')
      await setWlStatus('needs_snapshot')
      await onRefresh()
      await onWorkloadUpdated?.()
      setStopSnapshotOpen(false)
      notifications.show({
        color: 'teal',
        title: 'Snapshot stopped',
        message: 'Files removed from dest_dir. Pick type if needed, then Download again.',
      })
    } catch (e) {
      setError(String((e as Error).message || e))
    } finally {
      setStoppingSnapshot(false)
    }
  }

  async function retryStart() {
    setError(null)
    nodeStartSent.current = false
    setRunning(true)
    setUiStep('start')
    agentAckedStep.current = 'start'
    startInstallWatch()
    pushLog('Retry start — snapshot directory kept')
    try {
      const started = await api.workloadsStart({
        workload_id: workload?.id,
        server_id: workload?.server_id,
        env,
      })
      if (!started.ok) {
        throw new Error(started.message || started.error || 'start failed')
      }
      if ((started.action || started.agent?.action) === 'snapshot') {
        pushLog('Start deferred — official snapshot running')
        agentAckedStep.current = 'snapshot'
        setUiStep('snapshot')
        await setWlStatus('snapshot_running')
        await onRefresh()
        await onWorkloadUpdated?.()
        return
      }
      const afterStart = await waitPanelLifecycleAck(agentTarget, 'start', {
        timeoutMs: 90_000,
        acceptCurrent: ['ibd', 'run', 'healthy'],
      })
      const next = wizardStepFromAgentLifecycle(afterStart, allowSnap) || 'start'
      agentAckedStep.current = next === 'install' ? 'start' : next
      setUiStep(agentAckedStep.current)
      await setWlStatus('starting')
      markInstallOk()
      await onRefresh()
      await onWorkloadUpdated?.()
    } catch (e) {
      failLane('start', String((e as Error).message || e))
      await onWorkloadUpdated?.()
    }
  }

  async function stopNodeStart() {
    pushLog('Stop start requested')
    try {
      const res = await api.nodeStop(agentTarget)
      if (!res.ok) {
        throw new Error(res.error || 'stop failed')
      }
      setRunning(false)
      setError(null)
      await onRefresh()
      await onWorkloadUpdated?.()
    } catch (e) {
      setError(String((e as Error).message || e))
    }
  }

  async function controlNodeProcess(action: 'stop' | 'start') {
    const id = workload?.id
    if (!id || nodeProcessBusy) return
    if (action === 'start' && nodeUnitRunning) return
    if (action === 'stop' && !nodeUnitRunning) return
    setNodeProcessBusy(true)
    setNodeProcessError(null)
    pushLog(action === 'stop' ? 'Stop node unit requested' : 'Start node (re-apply unit + restart) requested')
    try {
      const res =
        action === 'stop'
          ? await api.workloadsNodeProcessStop(id)
          : await api.workloadsNodeProcessStart(id)
      if (res.ok === false) {
        throw new Error(res.message || res.error || `${action} failed`)
      }
      setNodeUnitRunning(action === 'start')
      notifications.show({
        color: 'teal',
        message:
          action === 'stop'
            ? 'Node unit stopped'
            : `Node started (pid ${res.pid ?? '—'})`,
        autoClose: 2500,
      })
      await onRefresh()
      await onWorkloadUpdated?.()
    } catch (e) {
      const msg = String((e as Error).message || e)
      setNodeProcessError(msg)
      notifications.show({ color: 'red', message: msg, autoClose: 4000 })
    } finally {
      setNodeProcessBusy(false)
    }
  }

  function retryLane(id: SetupLaneId) {
    const action = retryActionForLane(id)
    setError(null)
    setPortsError(null)
    if (action === 'check_ports') {
      // Kotlin-managed nodes have a real per-network port catalog — just re-check it,
      // never fall through to the legacy tip-agent plan/provision call.
      if (usingLiveNodeCatalog && workload?.id) {
        void checkHostPortsLive()
        return
      }
      startInstallWatch()
      void installWithPortCheck()
      return
    }
    if (action === 'provision') {
      setUiStep(allowSnap ? 'snapshot' : 'start')
      agentAckedStep.current = allowSnap ? 'snapshot' : 'start'
      startInstallWatch()
      void installWithPortCheck()
      return
    }
    if (action === 'snapshot_start') {
      void retrySnapshot()
      return
    }
    if (action === 'node_start') {
      void retryStart()
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
        setUiStep(stillSyncingInWizard(status) || isOnline(status) ? 'sync' : 'start')
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
          const acked = await waitPanelLifecycleAck(agentTarget, 'start', {
            timeoutMs: 90_000,
            acceptCurrent: ['run', 'healthy'],
          })
          const next = wizardStepFromAgentLifecycle(acked, allowSnap) || 'start'
          agentAckedStep.current = next === 'done' ? 'sync' : next
          setUiStep(next === 'done' ? 'sync' : next)
          await setWlStatus('starting')
          pushLog('Agent ACK: start started/done')
          markInstallOk()
          await onRefresh()
          await onWorkloadUpdated?.()
        } catch (e) {
          const msg = String((e as Error).message || e)
          if (/snapshot_required|snapshot is required/i.test(msg)) {
            pushLog('Start blocked — requesting official snapshot…')
            try {
              await requestSnapshotStart()
              agentAckedStep.current = 'snapshot'
              setUiStep('snapshot')
              await setWlStatus('snapshot_running')
              nodeStartSent.current = false
              await onRefresh()
              await onWorkloadUpdated?.()
              return
            } catch (snapErr) {
              const snapMsg = String((snapErr as Error).message || snapErr)
              setError(snapMsg)
              pushLog(`Snapshot start failed: ${snapMsg}`)
              markInstallFail(snapMsg)
              setRunning(false)
              await onWorkloadUpdated?.()
              return
            }
          }
          failLane('start', msg)
          await onWorkloadUpdated?.()
        }
        return
      }

      if (snapRunning(status, snapshotIdleAfterStop) || running) {
        const fromAgent = wizardStepFromAgentLifecycle(status, allowSnap)
        if (
          fromAgent === 'snapshot' ||
          fromAgent === 'start' ||
          fromAgent === 'sync'
        ) {
          setUiStep(fromAgent)
        }
      }
    }

    void tick()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [allowSnap, status?.snapshot?.ready, status?.snapshot?.wget_running, status?.snapshot?.failed, status?.snapshot?.aborted, status?.snapshot?.phase, status?.rpc?.reachable, status?.rpc?.http_ok, running, snapshotIdleAfterStop])

  useEffect(() => {
    const phase = (status?.snapshot?.phase || '').toLowerCase()
    if (phase === 'aborted' || status?.snapshot?.aborted) {
      setSnapshotIdleAfterStop(true)
      return
    }
    if (snapshotDownloadLive(status)) {
      setSnapshotIdleAfterStop(false)
    }
  }, [
    status?.snapshot?.phase,
    status?.snapshot?.aborted,
    status?.snapshot?.busy,
    status?.snapshot?.wget_running,
    status?.snapshot?.can_stop,
    status?.snapshot?.can_start,
    status?.node_status,
  ])

  // Resume from agent lifecycle, but never regress behind panel SQLite (sync → Snapshot).
  useEffect(() => {
    if (autoStarted.current || running || portsConfirming) return
    // Never auto-skip Confirm ports on a fresh / awaiting_ports row.
    if (status?.needs_provision || (workload?.status || '').toLowerCase() === 'awaiting_ports') {
      return
    }
    const fromPanel = wizardStepFromPanelStatus(workload?.status, allowSnap)
    if (fromPanel === 'sync' || fromPanel === 'done') {
      agentAckedStep.current = 'sync'
      setUiStep('sync')
      return
    }
    if (fromPanel === 'start') {
      agentAckedStep.current = 'start'
      setUiStep('start')
      return
    }
    const fromAgent = wizardStepFromAgentLifecycle(status, allowSnap)
    const visible = wizardVisibleStep(fromAgent, allowSnap)
    if (!visible || visible === 'ports' || visible === 'done') {
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
    if (fromAgent === 'start' || fromAgent === 'sync' || fromAgent === 'snapshot' || fromAgent === 'install') {
      autoStarted.current = true
      setRunning(true)
      agentAckedStep.current = visible
      setUiStep(visible)
      startInstallWatch()
      pushLog(`Resumed from agent lifecycle (${visible})`)
    }
  }, [status, running, portsConfirming, allowSnap, currentStep?.detail, workload?.status])

  const idx = active ? stepIdxOf(active) : -1
  const failedLane = setupLaneFailedId(status, allowSnap, {
    portsError: usingLiveNodeCatalog ? null : portsError,
    wizardError: error,
  })
  const failedWizard = failedLane
    ? wizardVisibleStep(wizardStepFromFailedLane(failedLane), allowSnap)
    : null

  const wizardApi = {
    active,
    agentAckedStep,
    agentPort,
    agentTarget,
    agentVer,
    allowSnap,
    applyDiskLayout,
    applyPortCheck,
    applyRecommendedSysctl,
    askAgentPorts,
    autoStarted,
    beginInstall,
    buildLogLines,
    buildLogPath,
    buildLogScroller,
    busyWhoisCmd,
    catalogPortsList,
    channelLatest,
    checkHostPortsLive,
    checkedPorts,
    clientConfig,
    clientConfigPorts,
    clientConfigRows,
    clientsAutoStarted,
    clientsError,
    clientsFiles,
    clientsPath,
    clientsSynced,
    clientsSyncing,
    confirmAgentUpdate,
    confirmKillHolder,
    confirmStopSnapshot,
    continueFromClients,
    continueFromDisks,
    continueFromNodeType,
    continueFromSnapshot,
    continueFromStart,
    testConnectBusy,
    testConnectConfig,
    testConnectResult,
    l1ParentChoices,
    l1ParentPickHelp,
    l1ParentLoading,
    applyL1ParentChoice,
    wantsL1ParentPicker,
    controlNodeProcess,
    copyWizardLogs,
    currentStep,
    diskError,
    diskInsights,
    diskLayout,
    diskLayoutPayload,
    diskLayoutSelected,
    diskLoading,
    diskMounts,
    diskNofile,
    diskRecommended,
    diskRoles,
    diskRows,
    diskRules,
    diskSaveTimer,
    diskSaved,
    diskSaving,
    diskSnapshotHint,
    diskSummary,
    diskUnused,
    disksContinueReady,
    displayLog,
    displayLogJoined,
    enterClientsStep,
    env,
    error,
    failLane,
    failedLane,
    failedWizard,
    goBackToClientsOrEarlier,
    goBackToNodeTypeOrDisks,
    goToDisksStep,
    hostSysctl,
    hostSysctlBelowRecommended,
    hostSysctlError,
    idx,
    installError,
    installGroups,
    installModalOpen,
    installOptions,
    installOutcome,
    installWithPortCheck,
    killTarget,
    killing,
    latestVer,
    loadHostDisks,
    loadHostSysctl,
    loadProgramCatalogPorts,
    loadSnapshotPlan,
    loadSnapshotSpeedProbe,
    log,
    manualBackToClients,
    manualBackToDisks,
    manualBackToNodeType,
    manualBackToPorts,
    mapNodePortsResponse,
    markInstallFail,
    markInstallOk,
    networkId,
    nodeHeight,
    nodeHttp,
    nodeLogLines,
    nodeLogPath,
    nodeLogScroller,
    nodePortsCatalog,
    nodePortsChecking,
    nodePortsLiveFetched,
    nodeProcessBusy,
    nodeProcessError,
    nodeStartSent,
    nodeTypeOptionGroups,
    nodeUnitRunning,
    onRefresh,
    onSetupComplete,
    onWorkloadUpdated,
    optionalPortBindings,
    p2p,
    persistSnapshotType,
    ports,
    portsConfirmCountdown,
    portsConfirming,
    portsError,
    portsFetched,
    portsLoading,
    portsOverallStatus,
    programCatalogPorts,
    pub,
    pushLog,
    reachNote,
    refreshPortCheck,
    refreshSnapshotProgress,
    requestSnapshotStart,
    retryLane,
    retrySnapshot,
    retryStart,
    running,
    saveDiskLayoutNow,
    seedClientConfigOptions,
    selectedSnapshotSource,
    server,
    serverLabel,
    serverURL,
    setBuildLogLines,
    setBuildLogPath,
    setChannelLatest,
    setCheckedPorts,
    setClientConfig,
    setClientsError,
    setClientsFiles,
    setClientsPath,
    setClientsSynced,
    setClientsSyncing,
    setDiskError,
    setDiskInsights,
    setDiskLayout,
    setDiskLoading,
    setDiskMounts,
    setDiskNofile,
    setDiskRecommended,
    setDiskRoles,
    setDiskRows,
    setDiskRules,
    setDiskSaved,
    setDiskSaving,
    setDiskSnapshotHint,
    setDiskSummary,
    setDiskUnused,
    setError,
    setHostSysctl,
    setHostSysctlError,
    setInstallError,
    setInstallGroups,
    setInstallModalOpen,
    setInstallOptions,
    setInstallOutcome,
    setKillTarget,
    setKilling,
    setLog,
    setNodeHeight,
    setNodeLogLines,
    setNodeLogPath,
    setNodePortsCatalog,
    setNodePortsChecking,
    setNodeProcessBusy,
    setNodeProcessError,
    setNodeUnitRunning,
    setPorts,
    setPortsConfirmCountdown,
    setPortsConfirming,
    setPortsError,
    setPortsLoading,
    setProgramCatalogPorts,
    setReachNote,
    setRunning,
    setSnapshotIdleAfterStop,
    setSnapshotPlan,
    setSnapshotPlanError,
    setSnapshotPlanLoading,
    setSnapshotProgress,
    setSnapshotSourceId,
    setSnapshotSpeedById,
    setSnapshotStarting,
    setStartApplyError,
    setStartBuildPending,
    setStartSaving,
    setStopSnapshotOpen,
    setStoppingSnapshot,
    setUiStep,
    setUnsupported,
    setUpdateOpen,
    setUpdating,
    setWizardLogCopied,
    setWlStatus,
    setXrplHistory,
    setupCompleteNotified,
    snapshotDownloadReady,
    snapshotIdleAfterStop,
    snapshotOptionGroups,
    snapshotPlan,
    snapshotPlanError,
    snapshotPlanLoading,
    snapshotPollTimer,
    snapshotProgress,
    snapshotSourceId,
    snapshotSpeedById,
    snapshotStarting,
    snapshotViaNode,
    startApplyError,
    startBuildPending,
    startInstallWatch,
    startPanelSnapshotDownload,
    startSaving,
    startSnapshotPolling,
    status,
    statusReady,
    stepIdxOf,
    steps,
    stopNodeStart,
    stopSnapshotOpen,
    stopSnapshotPolling,
    stoppingSnapshot,
    syncClientsToHost,
    sysctlKeyByOption,
    uiStep,
    unsupported,
    updateOpen,
    updating,
    usingLiveNodeCatalog,
    wantsDiskLayout,
    wantsInstallOptions,
    wantsNodeTypeStep,
    wantsXrplHistory,
    wizardLogCopied,
    wizardLogCopiedTimer,
    wizardLogScroller,
    workload,
    xrplHistory,
    // Explicit UI fields (must be on the bag for shell / SyncStep)
    needsAgentUpdate,
    nodeTypeStepLabel,
    installBusy,
    stepPending,
    syncProgress,
    syncingInWizard,
    progress,
  }


  return wizardApi
}
