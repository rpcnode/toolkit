import type { MetricsPayload, StatusPayload } from './types'

async function getJSON<T>(url: string): Promise<T> {
  const res = await fetch(url, { cache: 'no-store', credentials: 'include' })
  if (!res.ok) {
    const data = (await res.json().catch(() => ({}))) as { error?: string; message?: string }
    throw new Error(data.message || data.error || `${url} → ${res.status}`)
  }
  return res.json() as Promise<T>
}

async function postJSON<T>(url: string, body?: unknown): Promise<T> {
  const res = await fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  })
  const data = (await res.json().catch(() => ({}))) as T & { error?: string; message?: string }
  if (!res.ok) throw new Error(data.message || data.error || `HTTP ${res.status}`)
  return data
}

async function putJSON<T>(url: string, body?: unknown): Promise<T> {
  const res = await fetch(url, {
    method: 'PUT',
    credentials: 'include',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  })
  const data = (await res.json().catch(() => ({}))) as T & { error?: string; message?: string }
  if (!res.ok) throw new Error(data.message || data.error || `HTTP ${res.status}`)
  return data
}

export type NotifySubInfo = {
  key: string
  label: string
  description: string
}

export type NotifyThresholds = {
  disk_used_pct?: number
  cpu_high_pct?: number
  rpc_latency_p95_ms?: number
  rpc_error_rate_pct?: number
  /** Fullnode Go proxy requests/sec (rps_1m). */
  rpc_rps?: number
  /** Seconds of continuous unreachable before node.down (default 45). */
  node_down_hold_sec?: number
  /** Seconds of continuous healthy before node.up (default 20). */
  node_up_hold_sec?: number
}

export type NotifySettings = {
  ok?: boolean
  enabled?: boolean
  chat_id?: string
  has_token?: boolean
  token_hint?: string
  token_masked?: string
  token_decrypt_ok?: boolean
  verified?: boolean
  verified_at?: string
  subscriptions?: Record<string, boolean>
  thresholds?: NotifyThresholds
  last_error?: string
  key_source?: string
  key_path?: string
  subscription_catalog?: NotifySubInfo[]
  error?: string
}

export type RPCProxyStats = {
  rps_1m?: number
  rps_5m?: number
  in_flight?: number
  total?: number
  latency_p50_ms?: number
  latency_p95_ms?: number
  errors_4xx?: number
  errors_5xx?: number
  upstream_errors?: number
  http_502?: number
  http_503?: number
}

/** Per-node systemd IPAccounting (leaf status). */
export type NodeNetStats = {
  node_net_rx_mbps?: number
  node_net_tx_mbps?: number
  node_net_rx_bytes?: number
  node_net_tx_bytes?: number
}

export type AgentTarget = {
  env?: string
  node?: string
  server?: string
  network?: string
}

export type NodeConfigField = {
  key: string
  label?: string
  help?: string
  type?: string
  group?: string
  value?: string
  protected?: boolean
  options?: string[]
}

export type NodeConfigDocument = {
  id: string
  path?: string
  format?: string
  title?: string
  description?: string
  content?: string
  writable?: boolean
  restart_required?: boolean
  daemon_reload?: boolean
  missing?: boolean
  fields?: NodeConfigField[]
  protected_keys?: string[]
}

export type NodeConfigResponse = {
  ok?: boolean
  network?: string
  env?: string
  etc_dir?: string
  documents?: NodeConfigDocument[]
  restart?: string
  note?: string
  error?: string
  message?: string
}

export type NodeConfigSaveResponse = {
  ok?: boolean
  written?: string[]
  restart?: boolean
  accepted?: boolean
  message?: string
  error?: string
  node_restart?: { phase?: string; detail?: string; pct?: number; unit?: string; last_error?: string }
}

/** Target a registered server / node / env via panel → agent proxy. */
function withAgentTarget(url: string, opts?: AgentTarget): string {
  if (!opts?.env && !opts?.node && !opts?.server && !opts?.network) return url
  const u = new URL(url, window.location.origin)
  if (opts.server) u.searchParams.set('server', opts.server)
  if (opts.node) u.searchParams.set('node', opts.node)
  if (opts.network) u.searchParams.set('network', opts.network)
  if (opts.env) u.searchParams.set('env', opts.env)
  return u.pathname + u.search
}

function asAgentTarget(target?: string | AgentTarget): AgentTarget | undefined {
  if (target == null || target === '') return undefined
  if (typeof target === 'string') return { env: target }
  return target
}

export type DevApiCatalog = {
  ok?: boolean
  auth?: {
    panel_basic?: string
    api_token?: string
    agent_key?: string
    token_set?: boolean
    agent_download_url?: string
  }
  endpoints?: Array<{ method?: string; path?: string; desc?: string }>
}

export type DonateWallet = {
  network: string
  label?: string
  address: string
  note?: string
}

export type DonatePayload = {
  ok?: boolean
  updated_at?: string
  title?: string
  blurb?: string
  footer?: string
  wallets?: DonateWallet[]
  source?: string
  cached_at?: string
  error?: string
  message?: string
}

export type AuthStatus = {
  ok?: boolean
  needs_setup?: boolean
  authenticated?: boolean
  user?: string
  agent_download_url?: string
  links?: { rpcnode?: string }
  note?: string
}

/** Compact host metrics cached by panel (agent heartbeat). */
export type ServerMetrics = {
  cpu_pct?: number
  /** Run-queue pressure load_1/ncpu*100 when agent exposes it. */
  load_pct?: number
  /** Logical CPU count when agent exposes it (for load avg coloring). */
  ncpu?: number
  mem_pct?: number
  mem_used_mb?: number
  mem_total_mb?: number
  disk_used_pct?: number
  disk_used_gb?: number
  disk_total_gb?: number
  load_1?: number
  last_seen_at?: string
  collected_at?: string
}

/** Registered host agent (server) in the panel registry. */
export type RegistryNode = {
  id: string
  name?: string
  env?: string
  network?: string
  agent_url?: string
  agent_key?: string
  os?: string
  arch?: string
  os_pretty?: string
  /** Installed host agent version (from agent /healthz or /api/v1/agent). */
  agent_version?: string
  /** CDN TOOLKIT_VERSION cached by panel. */
  latest_agent_version?: string
  /** local agent_version older than CDN latest. */
  agent_update_available?: boolean
  created_at?: string
  updated_at?: string
  metrics?: ServerMetrics | null
  metrics_status?: 'online' | 'stale' | 'offline' | 'unknown' | string
  metrics_stale?: boolean
  nodes_count?: number
  can_delete?: boolean
}

export type CheckedCatalogPort = {
  port: number
  role: string
  label?: string
  external?: boolean
  bind?: string
  holder?: string
  pid?: string
  comm?: string
  cmdline?: string
  unit?: string
  killable?: boolean
  kill_blocked?: string
  reach?: string
  reach_reason?: string
}

export type PortHolderInfo = {
  ok?: boolean
  port?: number
  role?: string
  label?: string
  listening?: boolean
  holder?: string
  pid?: string
  comm?: string
  cmdline?: string
  unit?: string
  killable?: boolean
  kill_blocked?: string
  message?: string
  error?: string
  freed?: boolean
}

/** One host/tip/leaf/watchdog log stream from GET /api/v1/agent/logs (via panel). */
export type AgentLogStream = {
  id: string
  unit?: string
  label?: string
  path?: string
  source?: string
  lines?: string[]
}

export type ServerLogsResponse = {
  ok?: boolean
  version?: string
  lines?: number
  count?: number
  streams?: AgentLogStream[]
  server_id?: string
  agent_url?: string
  error?: string
  message?: string
}

export type ClientUpdateInfo = {
  local?: string
  latest?: string
  update_available?: boolean
  phase?: string
  detail?: string
  pct?: number
  last_error?: string
  channel?: string
}

export type Workload = {
  id: string
  server_id: string
  name?: string
  network: string
  env: string
  public_port?: number
  agent_port?: number
  node_http_port?: number
  p2p_port?: number
  agent_url?: string
  status?: string
  /** Fullnode client version (Agave / bitcoind / geth / …), cached from agent status. */
  client_version?: string
  client_latest?: string
  client_update_available?: boolean
  created_at?: string
  updated_at?: string
  /** First Install/provision (empty until then). */
  install_started_at?: string
  /** First honestly-synced / working (empty until then). */
  synced_at?: string
  /** Cached by panel-collector (SQLite node_status). */
  lifecycle_phase?: string
  lifecycle_label?: string
  lifecycle_detail?: string
  lifecycle_busy?: boolean
  height?: number | null
  snapshot_progress?: number | null
  status_error?: string
  status_at?: string
  /** false when last collector poll failed (tip/leaf). */
  agent_reachable?: boolean | null
  /** Cached Go fullnode proxy metrics from leaf agent. */
  rpc_proxy?: RPCProxyStats | null
  /** Per-node NIC rates from leaf status (IPAccounting). */
  node_net?: NodeNetStats | null
  /** Confirmed multi-disk layout (panel SQLite; set on Install/provision). */
  disk_layout?: ProvisionDiskLayout | MultiDiskLayoutPlan | null
}

export type WorkloadPort = {
  port?: number
  proto?: string
  role?: string
  open_in_firewall?: boolean
  desc?: string
}

/** Tip host block device from GET /api/v1/host/disks (lsblk). */
export type HostDiskInfo = {
  name: string
  path?: string
  model?: string
  size_bytes?: number
  size_human?: string
  tran?: string
  rota?: boolean
  type?: string
  mountpoint?: string
  fstype?: string
  fsavail_bytes?: number
  fsavail_human?: string
  fssize_bytes?: number
  fsused_pct?: number
  preferred?: boolean
}

export type HostMountInfo = {
  target: string
  source?: string
  fstype?: string
  size_bytes?: number
  avail_bytes?: number
  avail_human?: string
  used_pct?: number
  disk_name?: string
  disk_path?: string
  model?: string
  tran?: string
  rota?: boolean
  preferred?: boolean
}

/** One JBOD role from tip multi_disk_roles catalog. */
export type DiskRoleDef = {
  id: string
  label: string
  description?: string
  leaf?: string
}

export type DiskRolePlacement = {
  id: string
  label?: string
  description?: string
  leaf?: string
  mount?: string
  dir?: string
}

/** Recommended / confirmed multi-disk layout (Solana + profile-driven networks). */
export type MultiDiskLayoutPlan = {
  strategy?: string
  network?: string
  env?: string
  roles?: DiskRolePlacement[]
  roles_map?: Record<string, { dir?: string; mount?: string }>
  notes?: string[]
  // Solana / transport compat flat fields
  ledger_mount?: string
  accounts_mount?: string
  snapshots_mount?: string
  ledger_dir?: string
  accounts_dir?: string
  snapshots_dir?: string
  state_mount?: string
  index_mount?: string
  state_dir?: string
  index_dir?: string
}

/** @deprecated use MultiDiskLayoutPlan — kept for Solana call sites */
export type SolanaDiskLayoutPlan = MultiDiskLayoutPlan

/** Provision payload disk_layout — roles as map for Go `map[string]diskRoleIn`. */
export type ProvisionDiskLayout = Omit<MultiDiskLayoutPlan, 'roles'> & {
  roles?: Record<string, { dir?: string; mount?: string }>
}

export type RegistryUpsertInput = {
  id?: string
  name?: string
  env?: string
  network?: string
  agent_url: string
  /** Omit / empty on edit to reuse the key already stored in the panel registry. */
  agent_key?: string
  os?: string
  arch?: string
  os_pretty?: string
}

export const api = {
  authStatus: () => getJSON<AuthStatus>('/api/auth/status'),
  login: (username: string, password: string) =>
    postJSON<{ ok: boolean; user?: string }>('/api/auth/login', { username, password }),
  setupPassword: (username: string, password: string) =>
    postJSON<{ ok: boolean; user?: string }>('/api/auth/setup', { username, password }),
  logout: () => postJSON<{ ok: boolean }>('/api/auth/logout'),

  /** Panel CDN channel — same TOOLKIT_VERSION as curl|bash install/agent.sh (not host DB). */
  agentChannel: (opts?: { refresh?: boolean }) =>
    getJSON<{
      ok?: boolean
      version?: string
      channel?: string
      install_url?: string
      binaries_base?: string
      cached_at?: string
      note?: string
    }>(opts?.refresh ? '/api/agent/channel?refresh=1' : '/api/agent/channel'),

  /** Donate wallets from install CDN donate.json (panel proxy, short cache). */
  donate: (opts?: { refresh?: boolean }) =>
    getJSON<DonatePayload>(opts?.refresh ? '/api/donate?refresh=1' : '/api/donate'),

  registryList: () =>
    getJSON<{
      ok?: boolean
      items?: RegistryNode[]
      count?: number
      note?: string
      latest_agent_version?: string
    }>('/api/nodes'),
  registryProbe: (body: { agent_url: string; agent_key: string; network?: string }) =>
    postJSON<{
      ok?: boolean
      agent_url?: string
      os?: string
      arch?: string
      os_pretty?: string
      agent_version?: string
      agent_network?: string
      network?: string
      network_mismatch?: boolean
      warning?: string
      message?: string
    }>('/api/nodes/probe', body),
  registryUpsert: (body: RegistryUpsertInput) =>
    postJSON<{
      ok?: boolean
      item?: RegistryNode
      agent_network?: string
      network_mismatch?: boolean
      warning?: string
      note?: string
    }>('/api/nodes', body),
  registryDelete: (id: string) =>
    fetch(`/api/nodes/${encodeURIComponent(id)}`, { method: 'DELETE', credentials: 'include' }).then(
      async (res) => {
        const data = (await res.json().catch(() => ({}))) as { ok?: boolean; error?: string; message?: string }
        if (!res.ok) throw new Error(data.message || data.error || `HTTP ${res.status}`)
        return data
      },
    ),

  /** Tip agent log streams (api/system/watchdog/leaves) via panel → tip. */
  serverLogs: (serverId: string, opts?: { lines?: number; unit?: string }) => {
    const q = new URLSearchParams()
    if (opts?.lines != null) q.set('lines', String(opts.lines))
    if (opts?.unit) q.set('unit', opts.unit)
    const qs = q.toString()
    return getJSON<ServerLogsResponse>(
      `/api/servers/${encodeURIComponent(serverId)}/logs${qs ? `?${qs}` : ''}`,
    )
  },

  /** Ask panel-collector to enqueue a full poll ASAP (next loop ≤ interval). */
  collectorTick: () =>
    postJSON<{ ok?: boolean; forced?: boolean; note?: string; stats?: string }>('/api/collector/tick'),

  collectorStats: () =>
    getJSON<{
      ok?: boolean
      has_tick?: boolean
      last_stats?: string
      last_tick_at?: string
      age_sec?: number
      stale?: boolean
      stale_after_sec?: number
      hint?: string
    }>('/api/collector/stats'),

  workloadsList: () =>
    getJSON<{ ok?: boolean; items?: Workload[]; count?: number }>('/api/workloads'),
  workloadsGet: (id: string) =>
    getJSON<{ ok?: boolean; item?: Workload }>(`/api/workloads/${encodeURIComponent(id)}`),
  workloadsDiskLayout: (id: string) =>
    getJSON<{
      ok?: boolean
      node_id?: string
      network?: string
      env?: string
      disk_layout?: ProvisionDiskLayout | MultiDiskLayoutPlan | null
      error?: string
      message?: string
    }>(`/api/workloads/${encodeURIComponent(id)}/disk-layout`),
  workloadsSaveDiskLayout: (id: string, disk_layout: ProvisionDiskLayout | MultiDiskLayoutPlan) =>
    putJSON<{
      ok?: boolean
      node_id?: string
      disk_layout?: ProvisionDiskLayout | MultiDiskLayoutPlan | null
      item?: Workload
      error?: string
      message?: string
    }>(`/api/workloads/${encodeURIComponent(id)}/disk-layout`, { disk_layout }),
  workloadsHostDisks: async (opts: { server_id: string; network?: string; env?: string }) => {
    const q = new URLSearchParams()
    q.set('server_id', opts.server_id)
    if (opts.network) q.set('network', opts.network)
    if (opts.env) q.set('env', opts.env)
    const res = await fetch(`/api/workloads/host-disks?${q.toString()}`, {
      credentials: 'include',
      cache: 'no-store',
    })
    const data = (await res.json().catch(() => ({}))) as {
      ok?: boolean
      disks?: HostDiskInfo[]
      mounts?: HostMountInfo[]
      recommended?: MultiDiskLayoutPlan
      multi_disk_roles?: DiskRoleDef[]
      layout_rules?: string[]
      message?: string
      error?: string
      network?: string
      env?: string
    }
    if (!res.ok) {
      return { ...data, ok: false }
    }
    return data
  },
  workloadsCheckPorts: async (body: { server_id: string; network: string; env: string }) => {
    const res = await fetch('/api/workloads/check-ports', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    const data = (await res.json().catch(() => ({}))) as {
      ok?: boolean
      ports_free?: boolean
      busy_ports?: { port?: number; role?: string; holder?: string }[]
      checked_ports?: CheckedCatalogPort[]
      reach?: {
        probed?: boolean
        host?: string
        open_ok?: boolean
        message?: string
        filtered?: { port?: number; role?: string; label?: string }[]
        reachable?: { port?: number; role?: string; label?: string }[]
        skipped?: { port?: number; role?: string; label?: string }[]
      }
      message?: string
      error?: string
      public_port?: number
      agent_port?: number
      node_http_port?: number
      p2p_port?: number
    }
    if (!res.ok) {
      return { ...data, ok: false }
    }
    return data
  },
  workloadsPortHolder: async (body: {
    server_id: string
    network: string
    env: string
    port: number
  }) => {
    const res = await fetch('/api/workloads/port-holder', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    const data = (await res.json().catch(() => ({}))) as PortHolderInfo
    if (!res.ok) {
      return { ...data, ok: false }
    }
    return data
  },
  workloadsPortHolderKill: async (body: {
    server_id: string
    network: string
    env: string
    port: number
    pid?: string
    confirm: true
  }) => {
    const res = await fetch('/api/workloads/port-holder/kill', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    const data = (await res.json().catch(() => ({}))) as PortHolderInfo
    if (!res.ok) {
      return { ...data, ok: false }
    }
    return data
  },
  workloadsPlan: async (body: { server_id: string; network: string; env: string }) => {
    const res = await fetch('/api/workloads/plan', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    const data = (await res.json().catch(() => ({}))) as {
      ok?: boolean
      public_port?: number
      agent_port?: number
      node_http_port?: number
      p2p_port?: number
      captive_core_http_query_port?: number
      admin_port?: number
      drifted?: boolean
      ports?: {
        captive_core_http_query_port?: number
        admin_port?: number
        sol_http_port?: number
        metrics_port?: number
      }
      external_ports?: WorkloadPort[]
      internal_ports?: WorkloadPort[]
      next_after_provision?: string[]
      agent_network?: string
      network_mismatch?: boolean
      warning?: string
      message?: string
      error?: string
      hint?: string
      network?: string
      env?: string
      agent_version?: string
      supported_networks?: string[]
      supported_envs?: string[]
      agent?: { error?: string; message?: string; agent_version?: string; version?: string }
    }
    // Keep structured unsupported_* even on HTTP 4xx/502 — wizard shows Update CTA.
    if (!res.ok && data.ok !== false) {
      return {
        ...data,
        ok: false,
        error: data.error || `HTTP ${res.status}`,
        message: data.message || data.error || `HTTP ${res.status}`,
      }
    }
    if (!res.ok) {
      return { ...data, ok: false }
    }
    return data
  },
  workloadsProvision: async (body: {
    server_id: string
    network: string
    env: string
    name?: string
    public_port?: number
    agent_port?: number
    node_http_port?: number
    p2p_port?: number
    ledger_dir?: string
    accounts_dir?: string
    snapshots_dir?: string
    disk_layout?: ProvisionDiskLayout
  }) => {
    const res = await fetch('/api/workloads/provision', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    const data = (await res.json().catch(() => ({}))) as {
      ok?: boolean
      item?: Workload
      updated?: boolean
      external_ports?: WorkloadPort[]
      next_steps?: string[]
      agent_network?: string
      network_mismatch?: boolean
      warning?: string
      message?: string
      error?: string
      hint?: string
      agent_version?: string
      agent?: { error?: string; message?: string; agent_version?: string; version?: string }
    }
    if (!res.ok) {
      return { ...data, ok: false }
    }
    return data
  },
  workloadsUpsert: (body: Partial<Workload> & { server_id: string; network: string; env: string }) =>
    postJSON<{ ok?: boolean; item?: Workload; message?: string; error?: string }>('/api/workloads', body),
  workloadsStart: (body: { workload_id?: string; server_id?: string; env: string }) =>
    postJSON<{ ok?: boolean; item?: Workload; message?: string; error?: string }>('/api/workloads/start', body),
  workloadsSetStatus: (body: { id: string; status: string }) =>
    postJSON<{ ok?: boolean; item?: Workload }>('/api/workloads/status', body),
  workloadsDelete: (id: string) =>
    fetch(`/api/workloads/${encodeURIComponent(id)}`, { method: 'DELETE', credentials: 'include' }).then(
      async (res) => {
        const data = (await res.json().catch(() => ({}))) as { ok?: boolean; error?: string; message?: string }
        if (!res.ok) throw new Error(data.message || data.error || `HTTP ${res.status}`)
        return data
      },
    ),
  workloadsRemove: (body: {
    id: string
    mode?: 'wipe' | 'agents' | 'panel'
    delete_files?: boolean
    force?: boolean
  }) =>
    postJSON<{
      ok?: boolean
      accepted?: boolean
      status?: string
      id?: string
      deleted?: string
      delete_files?: boolean
      panel_only?: boolean
      agent_error?: string
      message?: string
      error?: string
      hint?: string
      agent?: { message?: string; steps?: string[]; removed_paths?: string[] }
      delete_files_async?: boolean
    }>('/api/workloads/remove', body),

  status: (target?: string | AgentTarget, opts?: { live?: boolean }) => {
    let url = withAgentTarget('/api/status.json', asAgentTarget(target))
    if (opts?.live) {
      url += url.includes('?') ? '&live=1' : '?live=1'
    }
    return getJSON<StatusPayload>(url)
  },
  metrics: (target?: string | AgentTarget) =>
    getJSON<MetricsPayload>(withAgentTarget('/api/metrics.json', asAgentTarget(target))),
  instances: () =>
    getJSON<{ ok?: boolean; agent_env?: string; items?: StatusPayload['instances'] }>('/api/instances.json'),
  snapshotStart: (target?: string | AgentTarget) =>
    postJSON<{ ok: boolean }>(withAgentTarget('/api/snapshot/start', asAgentTarget(target))),
  host: () => getJSON<{ ok?: boolean; host?: StatusPayload['host'] }>('/api/host'),
  publicBaseGet: () =>
    getJSON<{
      ok?: boolean
      public_base?: string
      panel_base?: string
      rpc_port?: number
      panel_port?: number
      host?: StatusPayload['host']
    }>('/api/public-base'),
  publicBaseApply: (body: { ip?: string; url?: string }) =>
    postJSON<{
      ok?: boolean
      public_base?: string
      panel_base?: string
      panel_status?: string
      env_updated?: boolean
      env_error?: string
      restart_hint?: string
      env_snippet?: string
      message?: string
      error?: string
    }>('/api/public-base', body),

  /** Host agent identity / CDN channel (panel proxies ?server=). */
  agentInfo: (target?: string | AgentTarget) =>
    getJSON<{
      ok?: boolean
      version?: string
      local_version?: string
      remote_version?: string
      update_available?: boolean
    }>(withAgentTarget('/api/v1/agent', asAgentTarget(target))),
  agentCheck: (target?: string | AgentTarget) =>
    postJSON<{
      ok?: boolean
      version?: string
      local_version?: string
      remote_version?: string
      update_available?: boolean
      message?: string
      error?: string
    }>(withAgentTarget('/api/v1/agent/check', asAgentTarget(target))),
  agentUpdate: (body?: { force?: boolean }, target?: string | AgentTarget) =>
    postJSON<{
      ok?: boolean
      updated?: boolean
      version?: string
      remote_version?: string
      message?: string
      error?: string
      steps?: string[]
    }>(withAgentTarget('/api/v1/agent/update', asAgentTarget(target)), body || {}),
  agentRestart: (target?: string | AgentTarget) =>
    postJSON<{ ok?: boolean; version?: string; message?: string; error?: string }>(
      withAgentTarget('/api/v1/agent/restart', asAgentTarget(target)),
    ),

  clientInfo: (target?: string | AgentTarget) =>
    getJSON<{ ok?: boolean; client_update?: ClientUpdateInfo }>(
      withAgentTarget('/api/v1/client', asAgentTarget(target)),
    ),
  clientCheck: (target?: string | AgentTarget) =>
    postJSON<{ ok?: boolean; client_update?: ClientUpdateInfo; error?: string }>(
      withAgentTarget('/api/v1/client/check', asAgentTarget(target)),
    ),
  clientUpdate: (target?: string | AgentTarget) =>
    postJSON<{ ok?: boolean; accepted?: boolean; client_update?: ClientUpdateInfo; error?: string }>(
      withAgentTarget('/api/v1/client/update', asAgentTarget(target)),
    ),
  nodeRestart: (target?: string | AgentTarget) =>
    postJSON<{
      ok?: boolean
      accepted?: boolean
      node_restart?: { phase?: string; detail?: string; pct?: number; unit?: string; last_error?: string }
      error?: string
    }>(withAgentTarget('/api/v1/node/restart', asAgentTarget(target))),

  nodeConfig: (target?: string | AgentTarget) =>
    getJSON<NodeConfigResponse>(withAgentTarget('/api/v1/node/config', asAgentTarget(target))),
  nodeConfigSave: (
    target: string | AgentTarget | undefined,
    body: {
      confirm: boolean
      restart?: boolean
      documents: Array<{ id: string; content: string; fields?: Record<string, string> }>
    },
  ) =>
    putJSON<NodeConfigSaveResponse>(
      withAgentTarget('/api/v1/node/config', asAgentTarget(target)),
      body,
    ),

  notifySettings: () => getJSON<NotifySettings>('/api/notifications/settings'),
  notifySaveSettings: (body: {
    bot_token?: string
    chat_id?: string
    enabled?: boolean
    subscriptions?: Record<string, boolean>
    thresholds?: NotifyThresholds
    clear_token?: boolean
  }) => putJSON<NotifySettings>('/api/notifications/settings', body),
  notifyTest: () =>
    postJSON<{ ok?: boolean; sent?: boolean; expires_at?: string; message?: string; error?: string }>(
      '/api/notifications/test',
    ),
  notifyVerify: (code: string) =>
    postJSON<NotifySettings>('/api/notifications/verify', { code }),

  devCatalog: () => getJSON<DevApiCatalog>('/api/v1'),
  developerDocsMd: async () => {
    const paths = ['/docs/developer-api.md', '/status/docs/developer-api.md']
    for (const p of paths) {
      const res = await fetch(p, { cache: 'no-store', credentials: 'include' })
      if (res.ok) return res.text()
    }
    throw new Error('developer-api.md not found')
  },
}
