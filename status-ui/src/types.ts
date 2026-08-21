export type MetricPoint = { t: number; v: number }

export type ControlAction = {
  available?: boolean
  reason?: string
  command?: string
}

export type PreflightCheck = {
  level?: string
  name?: string
  detail?: string
  recommend?: string
}

export type PreflightSummary = {
  ok?: number
  warn?: number
  fail?: number
  suitable?: boolean
  checked_at?: string
  env?: string
  blocking?: boolean
  checks?: PreflightCheck[]
  source?: string
  platform?: string
  hostname?: string
  context?: string
  hint?: string
}

export type InstanceInfo = {
  id?: string
  env?: string
  hostname?: string
  public_base_url?: string
  status_url?: string
  status_json_url?: string
  state_file?: string
  local_state?: boolean
  current?: boolean
  snapshot_enabled?: boolean
  registered?: boolean
  /** RPC public port (TRON_PUBLIC_PORT). */
  gateway_port?: number
  public_port?: number
  /** Ops panel port (TRON_PANEL_PORT). */
  panel_port?: number
  panel_base_url?: string
  node_http_port?: number
  gateway_listen?: string
  node_http?: string
}

export type SnapshotInfo = {
  enabled?: boolean
  ready?: boolean
  required?: boolean
  busy?: boolean
  pct?: string | number
  /** Alias used by panel proxy/collector (same meaning as pct). */
  progress_pct?: string | number
  eta?: string
  phase?: string
  detail?: string
  error?: string
  url?: string
  can_start?: boolean
  can_stop?: boolean
  log_tail?: string[]
  wget_running?: boolean
  failed?: boolean
  manual?: boolean
}

/** Agent-owned log feed (Bitcoin IBD / Solana validator snapshot / etc.). */
export type AgentLogsInfo = {
  title?: string
  source?: string
  lines?: string[]
  /** Primary host log path or journalctl -u … (agent-owned). */
  path?: string
  /** All relevant log paths on the host. */
  paths?: string[]
}

/** Bitcoin IBD / sync status from system-agent (bitcoind RPC). */
export type SyncInfo = {
  network?: string
  ibd?: boolean
  /** L2/HL eth_syncing or bootstrap — same UI meaning as ibd. */
  syncing?: boolean
  blocks?: number
  headers?: number
  block?: number
  verificationprogress?: number
  verification_pct?: number
  /** Some agents emit 0..1 fraction under this name (doge/cardano). */
  verify_pct?: number
  size_on_disk?: number
  size_on_disk_gb?: number
  peers?: number
  chain?: string
  ok?: boolean
  detail?: string
  updated_at?: string
  log_tail?: string[]
  slot?: number
  height?: number
  /** Solana getBlockHeight — confirmed blocks (not slot; skipped slots omitted). */
  block_height?: number
  epoch?: number
  /** Solana catch-up flag (same UI meaning as syncing/ibd). */
  catching?: boolean
  /** Solana: slots behind cluster tip (getHealth). */
  slots_behind?: number
  /** TRON / generic: blocks behind public tip. */
  blocks_behind?: number
  behind?: number
  /** Latest local block time (RFC3339) — TRON getnowblock timestamp. */
  block_time?: string
  /** Solana: cluster tip slot used for verification_pct. */
  cluster_slot?: number
  /** Toncoin: validator out-of-sync seconds (MyTonCtrl / getstats). */
  out_of_sync_sec?: number
  /** Toncoin: oos not shrinking (serializer / no peers). */
  catchup_stalled?: boolean
  /** Toncoin: MyTonCtrl dump download % from bootstrap.log (aria2/wget). */
  dump_pct?: number
  /** Stellar local ledger (alias of blocks). */
  latest_ledger?: number
  /** Stellar public tip ledger (alias of headers when tip known). */
  tip_ledger?: number
  /** XRPL server_info.complete_ledgers (`lo-hi` or `empty`). */
  complete_ledgers?: string
  /** XRPL server_state (connected / syncing / tracking / full). */
  server_state?: string
  /** XRPL validated ledger seq. */
  ledger_seq?: number
  /** XRPL history policy: stock | day | weeks | full | custom. */
  history_mode?: string
  /** XRPL ledger_history target (0 = full / genesis). */
  history_ledgers?: number
}

export type AgentStatusInfo = {
  role?: string
  version?: string
  status?: string
  activity?: string
  last_error?: string
  interval?: string
  internal?: string
}

export type LifecycleStep = {
  id?: string
  title?: string
  detail?: string
  status?: 'pending' | 'active' | 'done' | 'error' | 'skipped' | string
  done?: boolean
  active?: boolean
  error?: boolean
  /** Network/env-specific step that may be omitted on other profiles. */
  optional?: boolean
  required?: boolean
  pct?: number | string
  started_at?: string
  finished_at?: string
}

export type AgentCapabilities = {
  snapshot?: boolean
  ibd?: boolean
  supported_steps?: string[]
  [key: string]: unknown
}

export type LifecycleProfile = {
  network?: string
  env?: string
  include_snapshot?: boolean
  snapshot_required?: boolean
  /** Declared lifecycle step ids from network profile (agent ≥0.3.17). */
  supported_steps?: string[]
  step_ids?: string[]
  extra_steps?: string[]
  capabilities?: AgentCapabilities
  display_name?: string
  node_binary?: string
  service_prefix?: string
  agent_network?: string
}

export type LifecycleCurrentStep = {
  id?: string
  title?: string
  status?: string
  detail?: string
  pct?: number | string
}

export type LifecycleInfo = {
  phase?: string
  label?: string
  detail?: string
  busy?: boolean
  pct?: number | string
  height?: number | null
  node_status?: string
  complete?: boolean
  /** Agent current step id (e.g. snapshot). */
  current?: string
  current_step_id?: string
  current_index?: number
  total_steps?: number
  /** Optional richer current step object from newer agents. */
  current_step?: LifecycleCurrentStep
  steps?: LifecycleStep[]
  /** Declared step plan (same as healthz) — source of truth for UI filters. */
  supported_steps?: string[]
  capabilities?: AgentCapabilities
  /** Common + network-specific step plan from the agent. */
  profile?: LifecycleProfile
}

export type StatusPayload = {
  ok?: boolean
  health?: string
  ui_phase?: string
  env?: string
  agent_env?: string
  view_env?: string
  controls_local?: boolean
  updated_at?: string
  served_at?: string
  system_agent_stale?: boolean
  degraded?: boolean
  /** false when panel served last-known SQLite cache because the agent is down. */
  agent_reachable?: boolean
  /** true when payload came from panel node_status cache (not live agent). */
  cached?: boolean
  cached_at?: string
  /** Panel workload network vs host agent profile.network. */
  network_mismatch?: boolean
  /** Host Server agent answered; per-node agent not provisioned yet. */
  needs_provision?: boolean
  /** Multi-chain Server tip — no chain snapshot / node lifecycle. */
  host_tip?: boolean
  panel_network?: string
  agent_network?: string
  /** Declared lifecycle steps from agent healthz/status (profile-driven). */
  supported_steps?: string[]
  capabilities?: AgentCapabilities
  managed_by?: string
  output_size?: string
  note?: string
  message?: string
  error?: string
  public_base?: string
  instance?: Record<string, unknown>
  instances?: InstanceInfo[]
  services?: Record<string, string>
  preflight?: PreflightSummary
  agent_version?: string
  /** Fullnode client version from agent collect (not toolkit agent_version). */
  client_version?: string
  client_update?: {
    local?: string
    latest?: string
    update_available?: boolean
    phase?: string
    step?: string
    detail?: string
    pct?: number
    last_error?: string
    channel?: string
  }
  node_restart?: {
    phase?: string
    detail?: string
    pct?: number
    busy?: boolean
    unit?: string
    last_error?: string
  }
  agent?: AgentStatusInfo
  api_agent?: AgentStatusInfo
  snapshot?: SnapshotInfo
  /** Agent-owned log lines for the Logs panel (Bitcoin IBD etc.). */
  logs?: AgentLogsInfo
  /** Bitcoin sync / IBD snapshot from agent. */
  sync?: SyncInfo
  disk?: {
    total_gb?: number
    used_gb?: number
    free_gb?: number
    used_pct?: number
  }
  rpc?: {
    node_height?: number | null
    node?: string
    /** Fullnode client version string when nested under rpc. */
    client_version?: string
    version?: string
    ok?: boolean
    reachable?: boolean
    process_up?: boolean
    port_open?: boolean
    http_ok?: boolean
    p2p_port?: number
    block?: number
    blocks?: number
    headers?: number
    height?: number
    slot?: number
    block_height?: number
    syncing?: boolean
    initialblockdownload?: boolean
    verificationprogress?: number
    verification_pct?: number
    complete_ledgers?: string
    server_state?: string
    ledger_seq?: number
    size_on_disk?: number
    peers?: number
    chain?: string
    chain_id?: string
    detail?: string
  }
  checks?: {
    java_tron_process?: boolean
    node_process_up?: boolean
    node_port_open?: boolean
    node_http_ok?: boolean
    systemd_node?: string
    [key: string]: unknown
  }
  version?: Record<string, string>
  updater?: Record<string, unknown>
  maintenance?: {
    enabled?: boolean
    reason?: string
    phase?: string
  }
  pause?: {
    active?: boolean
    title?: string
    message?: string
  }
  connect?: {
    ready?: boolean
    base_url?: string
    rpc_base?: string
    panel_base?: string
    /** Go RPC proxy (clients). Not agent_port. */
    public_port?: number
    agent_port?: number
    rpc_port?: number
    panel_port?: number
    http_fullnode?: string
    getnowblock?: string
    internal_node?: string
    examples?: Record<string, string>
    note?: string
  }
  panel_base?: string
  node_status?: string
  lifecycle?: LifecycleInfo
  setup?: {
    complete?: boolean
    phase?: string
    steps?: Array<{ id?: string; title?: string; detail?: string; done?: boolean; active?: boolean; pct?: number | string }>
    lifecycle_steps?: LifecycleStep[]
  }
  host?: {
    load_1?: number
    load_5?: number
    cpu_pct?: number
    mem_pct?: number
    mem_used_mb?: number
    mem_total_mb?: number
    hostname?: string
    primary_ip?: string
    os?: string
    arch?: string
    ips?: Array<{
      ip?: string
      iface?: string
      source?: string
      primary?: boolean
    }>
    detected_at?: string
  }
  metrics?: {
    rps_1m?: number
    rps_5m?: number
    in_flight?: number
    total?: number
    latency_p50_ms?: number
    latency_p95_ms?: number
    errors_4xx?: number
    errors_5xx?: number
    http_502?: number
    http_503?: number
    upstream_errors?: number
    maintenance_hits?: number
  }
  controls?: Record<string, ControlAction>
  paths?: Record<string, string>
}

export type HostDiskIO = {
  name: string
  read_iops?: number
  write_iops?: number
  read_mb_s?: number
  write_mb_s?: number
  util_pct?: number
  free_gb?: number
  total_gb?: number
  used_pct?: number
  mount?: string
}

export type HostDiskIOHistory = {
  name: string
  read_iops?: MetricPoint[]
  write_iops?: MetricPoint[]
  util?: MetricPoint[]
}

export type MetricsPayload = {
  ok?: boolean
  demo?: boolean
  updated_at?: string
  current?: {
    rps_1m?: number
    rps_5m?: number
    in_flight?: number
    latency_p50_ms?: number
    latency_p95_ms?: number
    load_1?: number
    load_5?: number
    cpu_pct?: number
    mem_pct?: number
    mem_used_mb?: number
    mem_total_mb?: number
    /** Host NIC receive throughput (Mbps). */
    net_rx_mbps?: number
    /** Host NIC transmit throughput (Mbps). */
    net_tx_mbps?: number
    net_rx_bps?: number
    net_tx_bps?: number
    /** Per-node unit IPAccounting receive (Mbps). */
    node_net_rx_mbps?: number
    /** Per-node unit IPAccounting transmit (Mbps). */
    node_net_tx_mbps?: number
    node_net_rx_bps?: number
    node_net_tx_bps?: number
    node_net_rx_bytes?: number
    node_net_tx_bytes?: number
    /** Per-node unit CPU % of host (cgroup). */
    node_cpu_pct?: number
    /** Per-node unit memory % of host RAM. */
    node_mem_pct?: number
    node_mem_used_mb?: number
    /** Host disk I/O — /proc/diskstats, whole physical disks. */
    disk_read_iops?: number
    disk_write_iops?: number
    disk_read_mb_s?: number
    disk_write_mb_s?: number
    /** Hottest disk %util (iostat). */
    disk_util_pct?: number
    disk_busy?: string
    /** Per physical disk (whole devices). */
    disks?: HostDiskIO[]
    /** Per-node unit cgroup io.stat. */
    node_disk_read_iops?: number
    node_disk_write_iops?: number
    node_disk_read_mb_s?: number
    node_disk_write_mb_s?: number
  }
  gateway?: {
    rps_1m?: number
    rps_5m?: number
    in_flight?: number
    total?: number
    latency_p50_ms?: number
    latency_p95_ms?: number
    errors_4xx?: number
    errors_5xx?: number
    http_502?: number
    http_503?: number
    upstream_errors?: number
    maintenance_hits?: number
  }
  history?: {
    rps?: MetricPoint[]
    load?: MetricPoint[]
    cpu?: MetricPoint[]
    memory?: MetricPoint[]
    net_rx?: MetricPoint[]
    net_tx?: MetricPoint[]
    node_net_rx?: MetricPoint[]
    node_net_tx?: MetricPoint[]
    node_cpu?: MetricPoint[]
    node_memory?: MetricPoint[]
    disk_read_iops?: MetricPoint[]
    disk_write_iops?: MetricPoint[]
    disk_util?: MetricPoint[]
    disks?: HostDiskIOHistory[]
    node_disk_read_iops?: MetricPoint[]
    node_disk_write_iops?: MetricPoint[]
  }
}
