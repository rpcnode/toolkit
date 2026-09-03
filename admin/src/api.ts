import type { StatusPayload } from './types'

const DEFAULT_API = 'http://127.0.0.1:8093'
const SESSION_KEY = 'rpcnode_session'
const SESSION_EXP_KEY = 'rpcnode_session_exp'

export function apiBase(): string {
  const raw = import.meta.env.VITE_API_URL
  if (raw === '') {
    return ''
  }
  return (raw ?? DEFAULT_API).trim().replace(/\/$/, '')
}

function apiUrl(path: string): string {
  const base = apiBase()
  return base ? `${base}${path}` : path
}

export function sessionToken(): string {
  try {
    const exp = localStorage.getItem(SESSION_EXP_KEY)
    if (exp) {
      const ms = Date.parse(exp)
      if (!Number.isNaN(ms) && ms < Date.now()) {
        forgetSession()
        return ''
      }
    }
    return localStorage.getItem(SESSION_KEY) || ''
  } catch {
    return ''
  }
}

function rememberSession(token?: string, expiresAt?: string) {
  if (!token) return
  try {
    localStorage.setItem(SESSION_KEY, token)
    if (expiresAt) localStorage.setItem(SESSION_EXP_KEY, expiresAt)
  } catch {
    /* private mode */
  }
}

function forgetSession() {
  try {
    localStorage.removeItem(SESSION_KEY)
    localStorage.removeItem(SESSION_EXP_KEY)
  } catch {
    /* ignore */
  }
}

function authHeaders(extra?: Record<string, string>): Headers {
  const headers = new Headers(extra)
  const token = sessionToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  return headers
}

function redactDevLog(value: unknown): unknown {
  if (value == null || typeof value !== 'object') return value
  if (Array.isArray(value)) return value.map(redactDevLog)
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
    if (/password|token|agent_key|github_token|githubToken|bot_token/i.test(k) && typeof v === 'string') {
      out[k] = '…'
    } else {
      out[k] = redactDevLog(v)
    }
  }
  return out
}

function logDevHttp(method: string, path: string, status: number, req?: unknown, res?: unknown) {
  if (!import.meta.env.DEV) return
  const payload: Record<string, unknown> = { status }
  if (req !== undefined) payload.req = redactDevLog(req)
  if (res !== undefined) payload.res = redactDevLog(res)
  console.debug(`[api] ${method} ${path} → ${status}`, payload)
}

/** Result of a panel API call — never throws; use in UI to show which request failed. */
export type ApiCallResult<T> = {
  ok: boolean
  status: number
  request: string
  data: T
  error?: string
  message?: string
}

export async function getJSONResult<T>(path: string): Promise<ApiCallResult<T>> {
  const request = `GET ${path}`
  try {
    const res = await apiFetch(path)
    const data = (await res.json().catch(() => ({}))) as T & { ok?: boolean; error?: string; message?: string }
    const bodyOk = data.ok !== false
    return {
      ok: res.ok && bodyOk,
      status: res.status,
      request,
      data,
      error: data.error,
      message: data.message,
    }
  } catch (e) {
    return {
      ok: false,
      status: 0,
      request,
      data: {} as T,
      message: e instanceof Error ? e.message : String(e),
    }
  }
}

async function postJSONResult<T>(path: string, body?: unknown): Promise<ApiCallResult<T>> {
  const request = `POST ${path}`
  try {
    const res = await apiFetch(path, {
      method: 'POST',
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })
    const data = (await res.json().catch(() => ({}))) as T & { ok?: boolean; error?: string; message?: string }
    const bodyOk = data.ok !== false
    return {
      ok: res.ok && bodyOk,
      status: res.status,
      request,
      data,
      error: data.error,
      message: data.message,
    }
  } catch (e) {
    return {
      ok: false,
      status: 0,
      request,
      data: {} as T,
      message: e instanceof Error ? e.message : String(e),
    }
  }
}

async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const extra =
    init.headers instanceof Headers
      ? Object.fromEntries(init.headers.entries())
      : ((init.headers as Record<string, string> | undefined) ?? undefined)
  const headers = authHeaders(
    init.body != null && !extra?.['Content-Type'] && !extra?.['content-type']
      ? { 'Content-Type': 'application/json', ...extra }
      : extra,
  )
  const method = (init.method || 'GET').toUpperCase()
  const res = await fetch(apiUrl(path), {
    cache: 'no-store',
    ...init,
    credentials: 'include',
    headers,
  })
  if (import.meta.env.DEV) {
    const copy = res.clone()
    const data = await copy.json().catch(() => undefined)
    let req: unknown
    if (typeof init.body === 'string') {
      try {
        req = JSON.parse(init.body)
      } catch {
        req = init.body
      }
    }
    logDevHttp(method, path, res.status, req, data)
  }
  return res
}

async function getJSON<T>(path: string): Promise<T> {
  const res = await apiFetch(path)
  const data = (await res.json().catch(() => ({}))) as T & { error?: string; message?: string }
  if (!res.ok) throw new Error(data.message || data.error || `${apiUrl(path)} → ${res.status}`)
  return data
}

async function postJSON<T>(path: string, body?: unknown): Promise<T> {
  const res = await apiFetch(path, {
    method: 'POST',
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  const data = (await res.json().catch(() => ({}))) as T & { error?: string; message?: string }
  if (!res.ok) throw new Error(data.message || data.error || `HTTP ${res.status}`)
  return data
}

async function putJSON<T>(path: string, body?: unknown): Promise<T> {
  const res = await apiFetch(path, {
    method: 'PUT',
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  const data = (await res.json().catch(() => ({}))) as T & { error?: string; message?: string }
  if (!res.ok) throw new Error(data.message || data.error || `HTTP ${res.status}`)
  return data
}

async function delJSON<T>(path: string): Promise<T> {
  const res = await apiFetch(path, { method: 'DELETE' })
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

export type ClientRow = {
  network: string
  env: string
  program?: string
  /** Current pin (DB current_version). */
  pin: string
  tag?: string
  /** Probed upstream latest (DB latest_version). */
  latest?: string
  latest_tag?: string
  status: string
  source?: string
  notes?: string
  skip_reason?: string
  url?: string
  probe_error?: string
  probed_at?: string
  download_phase?: string
  download_name?: string
  download_bytes?: number
  download_total?: number
  download_pct?: number
  download_error?: string
}

export type NetworkEnvDetail = {
  id: string
  label?: string
  /** Catalog Env.DiskHintGiB — install / JBOD plan. */
  disk_hint_gib?: number
  /** Approximate full-node (or default flavor) footprint from install options. */
  full_node_gib?: number
  /** Archive / full-history flavor when the network offers one. */
  archive_gib?: number
  /** Recommended minimum host vCPU (catalog Env.CPUCores). */
  cpu_cores?: number
  /** Recommended minimum host RAM GiB (catalog Env.MemoryGiB). */
  memory_gib?: number
  /** never | required | optional */
  snapshot?: string
  /** Default Ethereum L1 execution RPC for Base/Arb Start (publicnode). */
  l1_rpc_url?: string
  /** Default Ethereum L1 beacon / blob API for Base/Arb Start. */
  l1_beacon_url?: string
  /** Operator hint for L1 parent picker on Start. */
  l1_pick_help?: string
  snapshot_url?: string
  install_options?: Array<{
    id: string
    label: string
    hint?: string
    default?: string
    choices?: Array<{
      id: string
      title: string
      hint?: string
      disk_gib?: number
      role?: string
      snapshot_url?: string
    }>
  }>
}

export type NetworkDiskRole = {
  id: string
  label: string
  /** Production disk class: nvme | ssd | hdd */
  media: string
}

export type NetworkCatalogItem = {
  id: string
  label: string
  envs: string[]
  env_details?: NetworkEnvDetail[]
  disk_roles?: NetworkDiskRole[]
  /** Default media when no roles (nvme | ssd). */
  disk_media?: string
  disk_notes?: string[]
  one_env_per_host?: boolean
  enabled?: boolean
  status?: string
  files_ready?: boolean
  /** Host/apt pin — no clients/<id> CDN package expected. */
  pin_only?: boolean
  /** Start-step bindings from chains/<id>/network.yml clientConfig. */
  client_config?: ClientConfigSpec | null
}

export type NetworksPayload = {
  ok?: boolean
  error?: string
  items?: NetworkCatalogItem[]
}

export type ClientsPayload = {
  ok?: boolean
  writer?: string
  source?: string
  writable?: boolean
  probing?: boolean
  interval?: string
  probed_at?: string
  error?: string
  github_token_set?: boolean
  /** Origin host dir (CLIENT_SYNC_DEST). Files live under dest/<network>/<env>/. */
  dest?: string
  rows?: ClientRow[]
  want?: number
  stats?: { total?: number; stale?: number; fail?: number; missing?: number; deleted?: number }
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

export type TelegramBot = {
  id: number
  username?: string
}

export type TelegramChat = {
  id: number
  type: string
  title: string
  username?: string
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

export type PanelInstall = {
  version?: string
  installed_at?: string
  updated_at?: string
}

export type SetupStatus = {
  ok?: boolean
  needed?: boolean
}

export type AuthStatus = {
  ok?: boolean
  authenticated?: boolean
  user?: string
  agent_download_url?: string
  install?: PanelInstall
  links?: { rpcnode?: string }
  note?: string
}

export type SetupCheck = {
  id: string
  label: string
  ok: boolean
  required?: boolean
  detail?: string
}

export type SetupCheckPayload = {
  ok?: boolean
  ready?: boolean
  checks?: SetupCheck[]
}

export type ServiceLink = {
  id: string
  label: string
  url: string
  ok?: boolean
}

export type PanelSettings = {
  ok?: boolean
  configured?: boolean
  panel_version?: string
  install?: PanelInstall
  install_origin?: string
  clients_base_url?: string
  install_base_url?: string
  agent_download_url?: string
  snapshot_cdn_origin?: string
  snapshot_cdn?: { origin?: string; ok?: boolean }
  github_token_set?: boolean
  github_token_decrypt_ok?: boolean
  github_token_masked?: string
  github_token_create_url?: string
  warning?: string
  curl?: string
  scripts?: { install?: string; update?: string; uninstall?: string }
  panel_scripts?: { install?: string; update?: string; uninstall?: string }
  presets?: { panel?: string; local?: string; prod?: string }
  links?: ServiceLink[]
  note?: string
}

/** Compact host metrics cached by panel (agent heartbeat). */
export type ServerDiskMetrics = {
  name?: string
  mount?: string
  free_gb?: number
  total_gb?: number
  used_pct?: number
  read_iops?: number
  write_iops?: number
  read_mb_s?: number
  write_mb_s?: number
  util_pct?: number
}

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
  disks?: ServerDiskMetrics[]
  last_seen_at?: string
  collected_at?: string
  net_rx_mbps?: number
  net_tx_mbps?: number
  disk_read_iops?: number
  disk_write_iops?: number
  disk_read_mb_s?: number
  disk_write_mb_s?: number
  disk_util_pct?: number
  disk_busy?: string
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
  /** Build inside that version: `<sha>[-dirty] <HH:MM>` from the build script. */
  agent_build?: string
  /** Latest agent version this panel ships (`chainAgentVersion`). */
  latest_agent_version?: string
  /** local agent_version older than latest. */
  agent_update_available?: boolean
  created_at?: string
  updated_at?: string
  metrics?: ServerMetrics | null
  metrics_status?: 'online' | 'stale' | 'offline' | 'unknown' | 'removing' | string
  metrics_stale?: boolean
  nodes_count?: number
  can_delete?: boolean
  remove_status?: 'removing' | string
}

/** A network/env pair already provisioned on the server host, not yet a panel workload. */
export type DiscoveredNode = {
  network: string
  env: string
  label?: string
  host_status?: string
  source?: string
  public_port?: number
  agent_port?: number
  node_http_port?: number
  p2p_port?: number
}

export type CheckedCatalogPort = {
  port: number
  role: string
  label?: string
  config?: string
  config_enabled_default?: boolean
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

/** Tip GET /api/v1/nodes/debug via panel (read-only host + network diagnose). */
export type NodeDebugFinding = {
  severity?: 'error' | 'warn' | 'info' | 'ok' | string
  scope?: 'host' | 'network' | string
  code?: string
  title?: string
  detail?: string
  hint?: string
}

export type NodeDebugLog = {
  id?: string
  label?: string
  path?: string
  lines?: string[]
  note?: string
}

export type NodeDebugUnit = {
  name?: string
  active?: string
  sub?: string
  result?: string
  nrestarts?: number
}

export type NodeDebugReport = {
  ok?: boolean
  network?: string
  env?: string
  collected_at?: string
  error_count?: number
  warn_count?: number
  findings?: NodeDebugFinding[]
  units?: NodeDebugUnit[]
  procs?: string[]
  logs?: NodeDebugLog[]
  error?: string
  message?: string
}

export type ClientUpdateInfo = {
  local?: string
  latest?: string
  previous_version?: string
  update_available?: boolean
  phase?: string
  step?: string
  detail?: string
  pct?: number
  last_error?: string
  log_tail?: string
  channel?: string
  events?: Array<{ id?: string; label?: string; detail?: string; at?: string }>
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
  status?: string
  /** From networks/<id>.yml — whether the install wizard should show a Snapshot step. */
  needs_snapshot?: boolean
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
  /** Public tip cached on the node row (panel probe). */
  network_height?: number | null
  /** Host IBD/snap progress 0..100; absent/-1 = unknown. Prefer over height/tip for the bar. */
  sync_pct?: number | null
  /** Host folder size for the node data directory (bytes); -1/absent = unknown. */
  size_on_disk?: number | null
  height_at?: string
  snapshot_progress?: number | null
  /** snapshot | start | sync | update | restart — owner of snapshot_progress. */
  progress_task?: string
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
  /** Wizard choices (snapshot flavor, xrpl_history, …) persisted at Install. */
  install_options?: Record<string, string> | null
  /** Last sync Node Test: "" | "pass" | "fail". */
  live_test_status?: string
  live_test_at?: string
  live_test_error?: string
}

export type WorkloadPort = {
  port?: number
  proto?: string
  role?: string
  open_in_firewall?: boolean
  desc?: string
}

/** One fixed catalog port for a node's network/env, checked live against the host agent. */
export type NodePortItem = {
  role: string
  port: number
  label: string
  free?: boolean | null
  holder?: string | null
  /** required | optional | none — from clients/*.yml ports[].config */
  config?: string
  config_enabled_default?: boolean
}

export type NodePortsResponse = {
  ok?: boolean
  items?: NodePortItem[]
  /** Primary rpc/http endpoint for this node, host resolved from the server's agent URL. */
  endpoint?: string | null
  error?: string
  message?: string
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
  planned_mount?: string
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
  kind?: string
  raid_level?: string
  layer?: string
  needs_format?: boolean
  /** Desktop / removable auto-mount (/media, /run/media): usable, but not mounted at boot. */
  auto_mount?: boolean
}

export type HostDiskInsight = {
  level?: 'good' | 'warn' | 'info' | string
  code?: string
  title?: string
  detail?: string
}

/** Tip pre-install check: fs.nr_open vs LimitNOFILE (systemd 205/LIMITS). */
export type HostNofileInfo = {
  nr_open?: number
  need?: number
  ok?: boolean
  checked?: boolean
  raised?: boolean
  detail?: string
}

/**
 * Live-probed snapshot archive size next to the static catalog hint — the
 * catalog number can go stale for months while a chain's archive keeps
 * growing (TRON mainnet outgrew its DiskHintGiB by more than 3x unnoticed).
 */
export type SnapshotSizeHint = {
  /** Static catalog Env.DiskHintGiB — the number the disk-role split uses. */
  catalog_gib?: number
  /** Just-probed (or cached ≤30 min) Content-Length of the snapshot URL. */
  archive_bytes?: number
  archive_human?: string
  /** 'content-length' | 'content-length-cache' | '' (probe unavailable). */
  source?: string
}

/** One JBOD role from tip multi_disk_roles catalog. */
export type DiskRoleDef = {
  id: string
  label: string
  description?: string
  leaf?: string
  /** Approximate size (GiB) this role is expected to use — picker hint, not a guarantee. */
  size_hint_gib?: number
}

/** Binding from chains/<id>/network.yml clientConfig — Start step preview. */
export type ClientConfigBinding = {
  path: string
  source: string
  description?: string | null
  role?: string | null
  option?: string | null
  value?: string | null
  relative?: string | null
  optional?: boolean
  default?: string | null
  /** snapshot_kind: values by type id / kind (full, lite, archive). */
  map?: Record<string, string>
  /** Emit binding only when install_options[this key] equals when_install_option_value (default "1"). */
  when_install_option?: string | null
  when_install_option_value?: string | null
  /** Live Test connect probe from network.yml testConnect. */
  test_connect?: ClientConfigTestConnect | null
}

export type ClientConfigTestConnect = {
  kind: string
  label?: string
  help?: string | null
}

export type ClientConfigSpec = {
  program?: string
  format?: string
  template?: string | null
  templates?: Record<string, string>
  env_sections?: Record<string, string>
  bindings?: ClientConfigBinding[]
}

export type DiskRolePlacement = {
  id: string
  label?: string
  description?: string
  leaf?: string
  mount?: string
  dir?: string
  /** Mirrors DiskRoleDef.size_hint_gib. */
  size_hint_gib?: number
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
  /** Public panel origin the host agent should push metrics to. */
  panel_url?: string
}

export const api = {
  authStatus: () => getJSON<AuthStatus>('/api/auth/status'),
  setupStatus: () => getJSON<SetupStatus>('/api/setup/status'),
  login: async (username: string, password: string) => {
    const res = await postJSON<{ ok: boolean; user?: string; token?: string; expires_at?: string }>(
      '/api/auth/login',
      {
        username,
        password,
      },
    )
    rememberSession(res.token, res.expires_at)
    return res
  },
  setup: async (username: string, password: string) => {
    const res = await postJSON<{ ok: boolean; user?: string; token?: string; expires_at?: string }>('/api/setup', {
      username,
      password,
    })
    rememberSession(res.token, res.expires_at)
    return res
  },
  /** @deprecated use setup() */
  setupPassword: (username: string, password: string) => api.setup(username, password),
  logout: async () => {
    const res = await postJSON<{ ok: boolean }>('/api/auth/logout')
    forgetSession()
    return res
  },

  panelSettings: () => getJSON<PanelSettings>('/api/settings'),
  savePanelSettings: (body: {
    install_origin?: string
    snapshot_cdn_origin?: string
    github_token?: string
    clear_github_token?: boolean
  }) => putJSON<PanelSettings>('/api/settings', body),

  /** Panel CDN channel — same chainAgentVersion as the agent JAR (not host DB). */
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
    }>('/api/servers'),
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
    }>('/api/servers/probe', body),
  registryUpsert: (body: RegistryUpsertInput) =>
    postJSON<{
      ok?: boolean
      item?: RegistryNode
      agent_network?: string
      network_mismatch?: boolean
      warning?: string
      note?: string
    }>('/api/servers', body),
  /** Update name / agent URL / key on an existing server (does not create a new row). */
  registryUpdate: (id: string, body: RegistryUpsertInput) =>
    putJSON<{
      ok?: boolean
      item?: RegistryNode
      error?: string
      message?: string
    }>(`/api/servers/${encodeURIComponent(id)}`, body),
  /** Nodes already provisioned on the host that this panel does not know about yet. */
  discoverServerNodes: (serverId: string) =>
    getJSON<{ ok?: boolean; items?: DiscoveredNode[]; count?: number; error?: string; message?: string }>(
      `/api/servers/${encodeURIComponent(serverId)}/discover`,
    ),
  registryDelete: (id: string) => delJSON(`/api/servers/${encodeURIComponent(id)}`),

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

  workloadsList: () =>
    getJSON<{ ok?: boolean; items?: Workload[]; count?: number }>('/api/nodes'),
  workloadsGet: (id: string) =>
    getJSON<{ ok?: boolean; item?: Workload }>(`/api/nodes/${encodeURIComponent(id)}`),
  /** Fixed catalog ports for the node's network/env, live-checked on the host agent. */
  nodePorts: (id: string) =>
    getJSON<NodePortsResponse>(`/api/nodes/${encodeURIComponent(id)}/ports`),
  checkHostPorts: (opts: { server_id: string; network: string; env: string }) => {
    const q = new URLSearchParams({
      server_id: opts.server_id,
      network: opts.network,
      env: opts.env,
    })
    return postJSON<NodePortsResponse>(`/api/host/ports/check?${q}`)
  },
  checkHostPortsResult: (opts: { server_id: string; network: string; env: string }) => {
    const q = new URLSearchParams({
      server_id: opts.server_id,
      network: opts.network,
      env: opts.env,
    })
    return postJSONResult<NodePortsResponse>(`/api/host/ports/check?${q}`)
  },
  workloadsDiskLayout: (id: string) =>
    getJSON<{
      ok?: boolean
      node_id?: string
      network?: string
      env?: string
      disk_layout?: ProvisionDiskLayout | MultiDiskLayoutPlan | null
      multi_disk_roles?: DiskRoleDef[]
      layout_rules?: string[]
      recommended?: MultiDiskLayoutPlan | null
      install_options?: Record<string, string> | null
      client_config?: ClientConfigSpec | null
      error?: string
      message?: string
    }>(`/api/nodes/${encodeURIComponent(id)}/disk-layout`),
  workloadsSaveDiskLayout: (id: string, disk_layout: ProvisionDiskLayout | MultiDiskLayoutPlan) =>
    putJSON<{
      ok?: boolean
      node_id?: string
      disk_layout?: ProvisionDiskLayout | MultiDiskLayoutPlan | null
      item?: Workload
      error?: string
      message?: string
    }>(`/api/nodes/${encodeURIComponent(id)}/disk-layout`, { disk_layout }),
  workloadsSaveInstallOptions: (
    id: string,
    body: { snapshot?: string; install_options?: Record<string, string> },
  ) =>
    putJSON<{
      ok?: boolean
      node_id?: string
      install_options?: Record<string, string> | null
      error?: string
      message?: string
    }>(`/api/nodes/${encodeURIComponent(id)}/install-options`, body),
  /** Save install_options, patch shipped template, write config on host — does not start the node. */
  workloadsApplyClientConfig: (
    id: string,
    body: { snapshot?: string; install_options?: Record<string, string> },
  ) =>
    postJSON<{
      ok?: boolean
      node_id?: string
      path?: string | null
      files?: string[]
      install_options?: Record<string, string> | null
      error?: string
      message?: string
    }>(`/api/nodes/${encodeURIComponent(id)}/client-config/apply`, body),
  /** Save install_options only, then start chain process on host (client must already be synced after Disks). */
  workloadsStartNode: (
    id: string,
    body: { install_options?: Record<string, string> },
  ) =>
    postJSON<{
      ok?: boolean
      node_id?: string
      path?: string | null
      pid?: number
      status?: string
      already_running?: boolean
      install_options?: Record<string, string> | null
      error?: string
      message?: string
    }>(`/api/nodes/${encodeURIComponent(id)}/start`, body),
  /** Node height + public tip — only while status is sync or active. */
  workloadsNodeHeight: (id: string) =>
    getJSON<{
      ok?: boolean
      node_id?: string
      status?: string
      height?: number
      height_at?: string
      network_height?: number | null
      behind?: number | null
      sync_pct?: number | null
      error?: string
      message?: string
    }>(`/api/nodes/${encodeURIComponent(id)}/height`),
  /** Host log tail (`logs/node.out` or optional relative [logFile] under node_dir). */
  workloadsNodeLogs: (id: string, opts?: { lines?: number; logFile?: string }) => {
    const q = new URLSearchParams()
    if (opts?.lines != null) q.set('lines', String(opts.lines))
    if (opts?.logFile) q.set('log_file', opts.logFile)
    const qs = q.toString()
    return getJSON<{
      ok?: boolean
      node_id?: string
      path?: string
      lines?: string[]
      truncated?: boolean
      error?: string
      message?: string
    }>(`/api/nodes/${encodeURIComponent(id)}/logs${qs ? `?${qs}` : ''}`)
  },
  /** Chain client version from host `{nodeDir}/VERSION`. */
  workloadsNodeClientVersion: (id: string) =>
    getJSON<{
      ok?: boolean
      node_id?: string
      client_version?: string
      path?: string
      error?: string
      message?: string
    }>(`/api/nodes/${encodeURIComponent(id)}/client-version`),
  /** systemctl stop for the node unit on the host (Sync step). */
  workloadsNodeProcessStop: (id: string) =>
    postJSON<{
      ok?: boolean
      node_id?: string
      pid?: number
      action?: string
      error?: string
      message?: string
    }>(`/api/nodes/${encodeURIComponent(id)}/process/stop`),
  /** systemctl start for an already-installed node unit (Sync step). */
  workloadsNodeProcessStart: (id: string) =>
    postJSON<{
      ok?: boolean
      node_id?: string
      pid?: number
      action?: string
      error?: string
      message?: string
    }>(`/api/nodes/${encodeURIComponent(id)}/process/start`),
  workloadsHostDisks: async (opts: { server_id: string }) => {
    const q = new URLSearchParams()
    q.set('server_id', opts.server_id)
    const res = await apiFetch(`/api/host/disks?${q.toString()}`)
    const data = (await res.json().catch(() => ({}))) as {
      ok?: boolean
      disks?: HostDiskInfo[]
      mounts?: HostMountInfo[]
      unused?: HostDiskInfo[]
      insights?: HostDiskInsight[]
      summary?: string
      message?: string
      error?: string
    }
    if (!res.ok) {
      return { ...data, ok: false }
    }
    return data
  },
  /** Live host /proc/sys vs Anza-recommended Solana tuning (Start sysctl knobs). */
  workloadsHostSysctl: async (opts: { server_id: string }) => {
    const q = new URLSearchParams()
    q.set('server_id', opts.server_id)
    const res = await apiFetch(`/api/host/sysctl?${q.toString()}`)
    const data = (await res.json().catch(() => ({}))) as {
      ok?: boolean
      current?: Record<string, number | null>
      recommended?: Record<string, number>
      install_option_keys?: Record<string, string>
      message?: string
      error?: string
    }
    if (!res.ok) {
      return { ...data, ok: false }
    }
    return data
  },
  workloadsDebug: async (opts: { server_id: string; network: string; env: string }) => {
    const q = new URLSearchParams()
    q.set('server_id', opts.server_id)
    q.set('network', opts.network)
    q.set('env', opts.env)
    const res = await apiFetch(`/api/nodes/debug?${q.toString()}`)
    const data = (await res.json().catch(() => ({}))) as NodeDebugReport
    if (!res.ok) {
      return { ...data, ok: false }
    }
    return data
  },
  /** Sync tip livecheck suite — pass/fail ACK for an active node. */
  workloadsTest: async (opts: {
    node_id?: string
    server_id?: string
    network?: string
    env?: string
  }) => {
    const res = await apiFetch('/api/nodes/test', {
      method: 'POST',
      body: JSON.stringify(opts),
    })
    const data = (await res.json().catch(() => ({}))) as {
      ok?: boolean
      network?: string
      env?: string
      error?: string
      message?: string
      live_test_status?: string
      live_test_at?: string
      live_test_error?: string
      checks?: Array<{
        id?: string
        title?: string
        ok?: boolean
        detail?: string
        error?: string
      }>
    }
    if (!res.ok) {
      return { ...data, ok: false }
    }
    return data
  },
  workloadsCheckPorts: async (body: { server_id: string; network: string; env: string }) => {
    const res = await apiFetch('/api/nodes/check-ports', {
      method: 'POST',
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
    const res = await apiFetch('/api/nodes/port-holder', {
      method: 'POST',
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
    const res = await apiFetch('/api/nodes/port-holder/kill', {
      method: 'POST',
      body: JSON.stringify(body),
    })
    const data = (await res.json().catch(() => ({}))) as PortHolderInfo
    if (!res.ok) {
      return { ...data, ok: false }
    }
    return data
  },
  workloadsPlan: async (body: { server_id: string; network: string; env: string }) => {
    const res = await apiFetch('/api/nodes/plan', {
      method: 'POST',
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
      install_options?: unknown
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
    xrpl_history?: string
    install_options?: Record<string, string>
  }) => {
    const res = await apiFetch('/api/nodes/provision', {
      method: 'POST',
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
    postJSON<{ ok?: boolean; item?: Workload; message?: string; error?: string }>('/api/nodes', body),
  workloadsStart: (body: { workload_id?: string; server_id?: string; env: string }) =>
    postJSON<{
      ok?: boolean
      item?: Workload
      message?: string
      error?: string
      action?: string
      agent?: { action?: string; message?: string }
    }>('/api/nodes/start', body),
  workloadsSetStatus: (body: { id: string; status: string }) =>
    postJSON<{ ok?: boolean; item?: Workload; status?: string; error?: string }>('/api/nodes/status', body),
  nodeSnapshotPlan: (id: string) =>
    getJSON<{
      ok?: boolean
      url?: string | null
      official_url?: string | null
      version?: string | null
      source?: string | null
      stream_unpack?: boolean | null
      size_bytes?: number | null
      dest_dir?: string | null
      status?: string | null
      type_id?: string | null
      default_source_id?: string | null
      via_node?: boolean
      sources?: Array<{
        id: string
        label: string
        url?: string | null
        version?: string | null
        size_bytes?: number | null
        stream_unpack?: boolean | null
        available?: boolean
        detail?: string | null
      }>
      snapshot_types?: Array<{
        id: string
        kind?: string
        label: string
        hint?: string
        disk_gib?: number
        default?: boolean
      }>
      error?: string
      message?: string
    }>(`/api/nodes/${encodeURIComponent(id)}/snapshot`),
  nodeSnapshotStart: (id: string, body?: { snapshot?: string; source?: string }) =>
    postJSON<{
      ok?: boolean
      type_id?: string
      url?: string
      dest_dir?: string
      error?: string
      message?: string
    }>(`/api/nodes/${encodeURIComponent(id)}/snapshot/start`, body ?? {}),
  nodeSnapshotProbe: (id: string, body?: { snapshot?: string; sources?: string[] }) =>
    postJSON<{
      ok?: boolean
      results?: Array<{
        id: string
        available?: boolean
        bytes_per_sec?: number | null
        sample_bytes?: number | null
        latency_ms?: number | null
        detail?: string | null
      }>
      error?: string
      message?: string
    }>(`/api/nodes/${encodeURIComponent(id)}/snapshot/probe`, body ?? {}),
  nodeSnapshotStop: (id: string) =>
    postJSON<{ ok?: boolean; error?: string; message?: string }>(
      `/api/nodes/${encodeURIComponent(id)}/snapshot/stop`,
    ),
  nodeSnapshotProgress: (id: string) =>
    getJSON<{
      ok?: boolean
      pct?: number | null
      phase?: string
      detail?: string
      ready?: boolean
      failed?: boolean
      error?: string
      status?: string
      message?: string
      log_tail?: string[]
    }>(`/api/nodes/${encodeURIComponent(id)}/snapshot/progress`),
  workloadsDelete: (id: string) => delJSON(`/api/nodes/${encodeURIComponent(id)}`),
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
    }>('/api/nodes/remove', body),

  instances: () =>
    getJSON<{ ok?: boolean; agent_env?: string; items?: StatusPayload['instances'] }>('/api/instances.json'),
  snapshotStart: (target?: string | AgentTarget) =>
    postJSON<{ ok: boolean }>(withAgentTarget('/api/snapshot/start', asAgentTarget(target))),
  snapshotStop: (target?: string | AgentTarget) =>
    postJSON<{ ok: boolean; error?: string }>(
      withAgentTarget('/api/snapshot/stop', asAgentTarget(target)),
    ),
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

  clientInfo: (nodeId: string) =>
    getJSON<{ ok?: boolean; client_update?: ClientUpdateInfo; error?: string; message?: string }>(
      `/api/nodes/${encodeURIComponent(nodeId)}/client/update`,
    ),
  clientCheck: (nodeId: string) =>
    getJSON<{ ok?: boolean; client_update?: ClientUpdateInfo; error?: string; message?: string }>(
      `/api/nodes/${encodeURIComponent(nodeId)}/client/update`,
    ),
  clientUpdate: (nodeId: string) =>
    postJSON<{
      ok?: boolean
      accepted?: boolean
      client_update?: ClientUpdateInfo
      error?: string
      message?: string
    }>(`/api/nodes/${encodeURIComponent(nodeId)}/client/update`, {}),
  clientUpdateRollback: (nodeId: string) =>
    postJSON<{
      ok?: boolean
      accepted?: boolean
      client_update?: ClientUpdateInfo
      error?: string
      message?: string
    }>(`/api/nodes/${encodeURIComponent(nodeId)}/client/update/rollback`, {}),
  nodeRestart: (target?: string | AgentTarget) =>
    postJSON<{
      ok?: boolean
      accepted?: boolean
      node_restart?: { phase?: string; detail?: string; pct?: number; unit?: string; last_error?: string }
      error?: string
    }>(withAgentTarget('/api/v1/node/restart', asAgentTarget(target))),
  nodeStop: (target?: string | AgentTarget) =>
    postJSON<{
      ok?: boolean
      accepted?: boolean
      node_restart?: { phase?: string; detail?: string; pct?: number; unit?: string; last_error?: string }
      error?: string
    }>(withAgentTarget('/api/v1/node/stop', asAgentTarget(target))),
  nodeStart: (target?: string | AgentTarget) =>
    postJSON<{
      ok?: boolean
      accepted?: boolean
      node_restart?: { phase?: string; detail?: string; pct?: number; unit?: string; last_error?: string }
      error?: string
    }>(withAgentTarget('/api/v1/node/start', asAgentTarget(target))),

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

  networks: () => getJSON<NetworksPayload>('/api/networks'),
  networksAll: () => getJSON<NetworksPayload>('/api/networks?all=1'),
  /** Live probe for Start clientConfig.bindings[].test_connect (eth_rpc / beacon_genesis). */
  networksTestConnect: (body: { kind: string; url: string }) =>
    postJSON<{
      ok?: boolean
      detail?: string | null
      error?: string
      message?: string
    }>('/api/networks/test-connect', body),
  /**
   * Active Ethereum nodes + env publicTip (RPC/beacon) for L2 Start pickers.
   * `status` defaults to active on the server.
   */
  networksEthereumNodes: (opts: { env: string; status?: string; server_id?: string }) => {
    const q = new URLSearchParams()
    q.set('env', opts.env)
    if (opts.status) q.set('status', opts.status)
    if (opts.server_id) q.set('server_id', opts.server_id)
    return getJSON<{
      ok?: boolean
      env?: string | null
      public?: { label: string; rpc: string; beacon?: string } | null
      items?: Array<{
        id: string
        name: string
        env: string
        status: string
        server_id: string
        same_host?: boolean
        rpc: string
        beacon: string
        public_endpoint?: string | null
      }>
      error?: string
      message?: string
    }>(`/api/networks/ethereum/nodes?${q}`)
  },
  /** @deprecated Prefer networksEthereumNodes — kept for older callers. */
  networksL1Parents: (opts: { network: string; env: string; server_id?: string }) => {
    const q = new URLSearchParams()
    q.set('network', opts.network)
    q.set('env', opts.env)
    if (opts.server_id) q.set('server_id', opts.server_id)
    return getJSON<{
      ok?: boolean
      l1_env?: string | null
      pick_help?: string | null
      choices?: Array<{
        id: string
        kind: string
        label: string
        rpc: string
        beacon: string
        status?: string | null
        same_host?: boolean
        node_id?: string | null
        server_id?: string | null
      }>
      error?: string
      message?: string
    }>(`/api/networks/l1-parents?${q}`)
  },
  networkSnapshot: (network: string, env: string, opts?: { source?: 'official' | 'prefer' }) =>
    getJSON<{
      ok?: boolean
      url?: string | null
      official_url?: string | null
      version?: string | null
      source?: string | null
      stream_unpack?: boolean | null
      size_bytes?: number | null
      error?: string
    }>(
      `/api/networks/snapshot?network=${encodeURIComponent(network)}&env=${encodeURIComponent(env)}${
        opts?.source === 'official' ? '&source=official' : ''
      }`,
    ),
  networkAction: (network: string, action: 'enable' | 'skip' | 'pending') =>
    postJSON<{ ok?: boolean; status?: string }>('/api/networks', { network, action }),
  networkInstall: (network: string, env?: string) =>
    postJSON<{
      ok?: boolean
      started?: boolean
      status?: string
      source?: string
      message?: string
      pin_only?: boolean
      error?: string
    }>('/api/networks/install', { network, env }),
  networkRemove: (network: string) => delJSON<{ ok?: boolean }>(`/api/networks/${encodeURIComponent(network)}`),
  setupCheck: () => getJSON<SetupCheckPayload>('/api/setup/check'),
  setupStage: (stage: string) => postJSON<{ ok?: boolean; setup_stage?: string }>('/api/setup/stage', { stage }),
  setupFinish: () => postJSON<{ ok?: boolean }>('/api/setup/finish'),
  clients: () => getJSON<ClientsPayload>('/api/clients'),
  clientsVersion: (network: string, env: string) =>
    getJSON<{
      ok?: boolean
      version?: string | null
      tag?: string | null
      source?: string | null
      error?: string
    }>(`/api/clients/version?network=${encodeURIComponent(network)}&env=${encodeURIComponent(env)}`),
  clientsPreview: (network: string, env: string) =>
    getJSON<ClientsPayload>(
      `/api/clients/preview?network=${encodeURIComponent(network)}&env=${encodeURIComponent(env)}`,
    ),
  clientsAdd: (network: string, env: string) =>
    postJSON<{
      ok?: boolean
      network?: string
      env?: string
      probe?: string
      error?: string
    }>('/api/clients', {
      network,
      env,
    }),
  clientsProbe: (body?: { network?: string; env?: string; program?: string }) =>
    postJSON<{ ok?: boolean; started?: boolean }>('/api/clients/probe', body ?? {}),
  clientsSync: (body?: { network?: string; env?: string; program?: string; force?: boolean }) =>
    postJSON<{ ok?: boolean; started?: boolean }>('/api/clients/sync', body ?? {}),
  clientsDelete: (network: string, env?: string) =>
    postJSON<{ ok?: boolean; purged?: boolean; dest?: string; removed?: string[]; error?: string }>(
      '/api/clients/delete',
      env ? { network, env } : { network },
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
  notifyConfigureTelegramBot: (bot_token: string) =>
    postJSON<TelegramBot>('/api/notifications/bot', { bot_token }),
  notifyDiscoverTelegramChats: () =>
    postJSON<{ ok?: boolean; chats?: TelegramChat[] }>('/api/notifications/chats'),
  notifySelectTelegramChat: (chat_id: number) =>
    postJSON<NotifySettings>('/api/notifications/chat', { chat_id }),

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
