import type {
  CheckedCatalogPort,
  ClientConfigBinding,
  ClientConfigSpec,
  DiskRoleDef,
  HostDiskInfo,
  HostMountInfo,
  MultiDiskLayoutPlan,
  Workload,
} from '../../api'
import type { StatusPayload } from '../../types'
import { nodeReadyForOps, snapshotDownloadLive } from '../../lib/nodeLifecycle'
import { supportsIbdStep } from '../../lib/network'
import { diskPlacements } from '../NodeDiskSummary'
import type {
  CatalogPortPolicy,
  ClientConfigPreviewRow,
  PlannedPorts,
  UnsupportedCapability,
} from './types'

/** Networks with tip multi_disk_roles (must match catalog DiskRoles). */
export const MULTI_DISK_NETWORKS = new Set([
  'solana',
  'ethereum',
  'polygon',
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
  'doge',
  'zcash',
  'xrpl',
  'stellar',
  'cardano',
  'etc',
  'hyperliquid',
])
export function detectUnsupportedCapability(res: {
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

export function sleep(ms: number) {
  return new Promise<void>((r) => setTimeout(r, ms))
}

/** Drop legacy “missing under …” prefix from Agave build-pending host messages. */
export function formatSolanaBuildPendingMessage(raw: string): string {
  const text = (raw || '').trim()
  if (!text) return text
  const stripped = text
    .replace(
      /^agave-validator missing under \S+\s*\(Anza stopped shipping it in solana-release tarballs since Agave v3\.0\)\.\s*/i,
      '',
    )
    .replace(/^agave-validator missing under \S+\s*\([^)]*\)\.\s*/i, '')
    .trim()
  return stripped || text
}

export function isCheckPortsTimeout(message?: string | null, error?: string | null): boolean {
  const blob = `${message || ''} ${error || ''}`
  return /agent_timeout|deadline exceeded|timed out|agent_unreachable/i.test(blob)
}

export function formatPortBusy(check: {
  message?: string
  error?: string
  busy_ports?: { port?: number; role?: string; holder?: string }[]
}): string {
  if (isCheckPortsTimeout(check.message, check.error)) {
    return (
      check.message ||
      'Tip agent timed out while checking ports. Retry Check ports — a busy first node must not block the second.'
    )
  }
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
export function busyListenWhoisCommands(ports: number[]): string {
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

/** Fixed catalog ports (Kotlin) — no tip public/agent pair; map roles for legacy provision payload. */
export function plannedPortsFromCatalog(catalog: CheckedCatalogPort[]): PlannedPorts | null {
  if (!catalog.length) return null
  const portOf = (role: string) => catalog.find((p) => p.role === role)?.port || 0
  const http =
    portOf('http_fullnode') ||
    portOf('upstream') ||
    catalog.find((p) => p.role.startsWith('http'))?.port ||
    0
  const p2p = portOf('p2p')
  const publicPort = portOf('public') || http
  const agentPort = portOf('agent')
  if (!publicPort && !agentPort && !p2p && !http) return null
  return {
    public_port: publicPort,
    agent_port: agentPort,
    node_http_port: http,
    p2p_port: p2p,
    source: 'catalog',
  }
}

export function resolveInstallPorts(
  usingLiveNodeCatalog: boolean,
  catalog: CheckedCatalogPort[],
  ports: PlannedPorts | null,
  workload: Workload | null,
): PlannedPorts | null {
  if (usingLiveNodeCatalog) {
    return plannedPortsFromCatalog(catalog) || ports
  }
  if (ports?.public_port) return ports
  if (workload?.public_port && workload.agent_port) {
    return {
      public_port: workload.public_port,
      agent_port: workload.agent_port,
      node_http_port: workload.node_http_port || 0,
      p2p_port: workload.p2p_port || 0,
      source: 'workload',
    }
  }
  return null
}

export function plannedMountOfDisk(d: HostDiskInfo): string {
  if (d.planned_mount) return d.planned_mount
  const n = (d.name || '').replace(/n1$/, '')
  return n ? `/data/${n}` : ''
}

export function unusedFromInventory(disks: HostDiskInfo[], mounts: HostMountInfo[]): HostDiskInfo[] {
  const root = mounts.find((m) => m.target === '/')?.disk_name
  const dataDisks = new Set(
    mounts
      .filter((m) => m.target && m.target !== '/' && !m.target.startsWith('/boot'))
      .map((m) => m.disk_name)
      .filter(Boolean) as string[],
  )
  return disks.filter((d) => {
    const n = (d.name || '').toLowerCase()
    if (!n.includes('nvme')) return false
    if (root && d.name === root) return false
    if (dataDisks.has(d.name)) return false
    if ((d.fstype || '').trim()) return false
    const mp = d.mountpoint || ''
    if (mp && mp !== '/' && !mp.startsWith('/boot')) return false
    return true
  }).map((d) => ({
    ...d,
    planned_mount: d.planned_mount || plannedMountOfDisk(d),
  }))
}

export function usableDiskLayout(
  plan: MultiDiskLayoutPlan | null | undefined,
  unused: HostDiskInfo[],
  hostMounts: HostMountInfo[] = [],
): MultiDiskLayoutPlan | null {
  if (!plan || plan.strategy === 'none') return null
  const roleMounts = Array.isArray(plan.roles) ? plan.roles.map((r) => r.mount) : []
  const mounts = [
    ...roleMounts,
    plan.ledger_mount,
    plan.accounts_mount,
    plan.snapshots_mount,
    plan.state_mount,
    plan.index_mount,
  ].filter(Boolean) as string[]
  if (!mounts.length) return null
  if (mounts.every((m) => m === '/')) return null
  if (unused.length > 0 && mounts.every((m) => m === '/' || m === '/data')) return null
  // A saved plan can name /data/nvme0 for a disk that has since been claimed by
  // the operator's data. Keeping it makes Install try to format that disk.
  if (hostMounts.length > 0) {
    const known = new Set<string>(['/', '/data'])
    for (const m of hostMounts) if (m.target) known.add(m.target)
    for (const d of unused) {
      const t = plannedMountOfDisk(d)
      if (t) known.add(t)
    }
    if (mounts.some((m) => !known.has(m))) return null
  }
  return plan
}

export function diskPlanRoleMounts(plan: MultiDiskLayoutPlan): Array<{ id: string; mount?: string }>
{
  if (Array.isArray(plan.roles))
  {
    return plan.roles.filter((r) => r?.id).map((r) => ({ id: r.id, mount: r.mount }))
  }
  const map =
    plan.roles_map ||
    (plan.roles && typeof plan.roles === 'object'
      ? (plan.roles as Record<string, { mount?: string }>)
      : {})
  return Object.entries(map).map(([id, v]) => ({ id, mount: v?.mount }))
}

export function isValidDataMount(mount: string | undefined): boolean
{
  if (!mount) return false
  if (mount === '/' || mount.startsWith('/boot')) return false
  return true
}

export function diskLayoutHasSelection(
  plan: MultiDiskLayoutPlan | null | undefined,
  roleDefs: DiskRoleDef[],
): boolean
{
  if (!plan) return false
  const placements = diskPlanRoleMounts(plan)
  if (placements.length === 0)
  {
    const flat = [
      plan.ledger_mount,
      plan.accounts_mount,
      plan.snapshots_mount,
      plan.state_mount,
      plan.index_mount,
    ].filter(Boolean) as string[]
    return flat.some(isValidDataMount)
  }
  if (roleDefs.length > 0)
  {
    return roleDefs.every((def) => {
      const mount = placements.find((p) => p.id === def.id)?.mount
      return isValidDataMount(mount)
    })
  }
  return placements.some((p) => isValidDataMount(p.mount))
}

export function joinConfigPath(base: string, relative?: string | null): string {
  const root = (base || '').replace(/\/+$/, '')
  const leaf = (relative || '').replace(/^\/+|\/+$/g, '')
  if (!root) return leaf ? `/${leaf}` : ''
  if (!leaf) return root
  return `${root}/${leaf}`
}

export function installOptionGateOpen(
  binding: ClientConfigBinding,
  options: Record<string, string>,
): boolean {
  const key = (binding.when_install_option || '').trim()
  if (!key) return true
  const want = (binding.when_install_option_value || '1').trim()
  return (options[key] || '0').trim() === want
}

export function portConfigInstallOptionKey(role: string): string {
  return `port_${role.trim().toLowerCase()}`
}

export function catalogPortConfigPolicy(port: CatalogPortPolicy | undefined): string {
  return (port?.config || 'required').trim().toLowerCase()
}

export function isCatalogPortBindingSource(source: string): boolean {
  const s = source.trim().toLowerCase()
  return s === 'catalog_port' || s === 'catalog_zmq_bind'
}

export function catalogPortConfigEnabled(
  role: string,
  ports: CatalogPortPolicy[],
  options: Record<string, string>,
): boolean {
  const spec = ports.find(
    (p) => String(p.role || '').toLowerCase() === role.trim().toLowerCase(),
  )
  const policy = catalogPortConfigPolicy(spec)
  if (policy === 'optional') {
    return (options[portConfigInstallOptionKey(role)] || '0').trim() === '1'
  }
  if (policy === 'none') return false
  return true
}

export function optionalCatalogPorts(ports: CatalogPortPolicy[]): CatalogPortPolicy[] {
  return ports.filter((p) => catalogPortConfigPolicy(p) === 'optional')
}

/** Resolve every clientConfig binding for the Start step (read-only preview + editable options). */
export function resolveClientConfigPreview(
  clientConfig: ClientConfigSpec | null | undefined,
  layout: MultiDiskLayoutPlan | null | undefined,
  ports: CatalogPortPolicy[],
  options: Record<string, string>,
  snapshotTypes: Array<{ id?: string; kind?: string }> = [],
): ClientConfigPreviewRow[] {
  const bindings = clientConfig?.bindings || []
  if (bindings.length === 0) {
    return diskPlacements(layout).map((p) => ({
      path: p.label,
      value: p.dir || p.mount || '—',
      description: 'Disk role path from Disks',
      source: 'disk_role_dir',
      detail: p.mount || '',
      editable: false,
    }))
  }
  const places = diskPlacements(layout)
  const byRole = new Map(places.map((p) => [p.id, p]))
  const byPortRole = new Map(
    ports
      .filter((p) => p.role && Number(p.port) > 0)
      .map((p) => [String(p.role).toLowerCase(), p]),
  )
  const snapId = (options.snapshot || '').trim().toLowerCase()
  const snapKind = (
    snapshotTypes.find((t) => (t.id || '').toLowerCase() === snapId)?.kind ||
    snapId
  )
    .trim()
    .toLowerCase()
  const out: ClientConfigPreviewRow[] = []
  for (const b of bindings) {
    const source = (b.source || '').toLowerCase()
    const path = b.path
    const description = (b.description || '').trim() || path
    const roleId = (b.role || '').trim().toLowerCase()
    if (!installOptionGateOpen(b, options)) continue
    if (isCatalogPortBindingSource(source) && roleId) {
      if (!catalogPortConfigEnabled(roleId, ports, options)) continue
    }
    const policy = roleId
      ? catalogPortConfigPolicy(ports.find((p) => String(p.role || '').toLowerCase() === roleId))
      : 'required'
    const portToggle =
      isCatalogPortBindingSource(source) && policy === 'optional'
        ? portConfigInstallOptionKey(roleId)
        : (b.when_install_option || '').trim() || undefined
    const alwaysOn =
      isCatalogPortBindingSource(source) && roleId
        ? policy === 'required'
        : !portToggle && source !== 'install_option'
    let value = ''
    let detail = ''
    let editable = false
    let option: string | undefined
    if (source === 'disk_role_dir') {
      const roleId = (b.role || '').trim()
      const place = byRole.get(roleId)
      value = joinConfigPath(place?.dir || place?.mount || '', b.relative)
      detail = [place?.label || roleId, place?.mount].filter(Boolean).join(' · ')
    } else if (source === 'disk_role_mount') {
      const roleId = (b.role || '').trim()
      const place = byRole.get(roleId)
      value = place?.mount || ''
      detail = place?.label || roleId
    } else if (source === 'catalog_port') {
      const roleId = (b.role || '').trim().toLowerCase()
      const port = byPortRole.get(roleId)
      value = port?.port && port.port > 0 ? String(port.port) : ''
      detail = port?.label || b.role || roleId
    } else if (source === 'catalog_zmq_bind') {
      const roleId = (b.role || '').trim().toLowerCase()
      const port = byPortRole.get(roleId)
      value =
        port?.port && port.port > 0 ? `tcp://127.0.0.1:${port.port}` : ''
      detail = port?.label || b.role || roleId
    } else if (source === 'install_option') {
      const opt = (b.option || '').trim()
      option = opt || undefined
      editable = !!opt
      value = (options[opt] || b.default || '').trim()
      detail = opt ? `option ${opt}` : 'install option'
    } else if (source === 'snapshot_kind') {
      const map = b.map || {}
      value = (map[snapKind] || map[snapId] || b.default || '').trim()
      detail = snapId
        ? `snapshot ${snapId}${snapKind && snapKind !== snapId ? ` · ${snapKind}` : ''}`
        : 'snapshot type (pick on Snapshot step)'
    } else if (source === 'literal') {
      value = (b.value || b.default || '').trim()
      detail = 'fixed'
    } else if (source === 'env_fact') {
      value = (b.value || b.default || '').trim()
      detail = 'env fact'
    } else {
      value = (b.value || b.default || '').trim()
      detail = source || 'unknown'
    }
    if (!value && b.optional && !portToggle) continue
    const tc = b.test_connect
    const testConnect =
      tc && (tc.kind || '').trim()
        ? {
            kind: String(tc.kind).trim(),
            label: (tc.label || 'Test connect').trim() || 'Test connect',
            help: (tc.help || '').trim() || undefined,
          }
        : undefined
    out.push({
      path,
      value: value || (b.default || '—'),
      description,
      source,
      detail,
      editable,
      option,
      portToggle,
      alwaysOn,
      testConnect,
    })
  }
  return out
}

export function bindingForCatalogPortRole(
  clientConfig: ClientConfigSpec | null | undefined,
  role: string,
): ClientConfigBinding | undefined {
  const roleId = role.trim().toLowerCase()
  return (clientConfig?.bindings || []).find(
    (b) =>
      isCatalogPortBindingSource(b.source || '') &&
      String(b.role || '').toLowerCase() === roleId,
  )
}
export const PORTS_CHECK_HELP =
  'Fixed catalog ports for this network/env (no remap). Bind is local LISTEN on the host. Reach is this panel dialing the node public IP (not a probe from the node). Filtered = cloud security group or host firewall. The agent does not open ports. Clients use Go RPC (public); Agent API is a separate control port.'

export function formatSnapshotBytes(bytes?: number | null): string {
  const n = Number(bytes || 0)
  if (!n) return '—'
  if (n >= 1024 ** 4) return `~${(n / 1024 ** 4).toFixed(1)} TiB`
  if (n >= 1024 ** 3) return `~${(n / 1024 ** 3).toFixed(1)} GiB`
  if (n >= 1024 ** 2) return `~${(n / 1024 ** 2).toFixed(0)} MiB`
  return `${n} B`
}

export function formatSnapshotSpeed(bytesPerSec?: number | null): string {
  const n = Number(bytesPerSec || 0)
  if (!n) return '—'
  if (n >= 1024 ** 3) return `${(n / 1024 ** 3).toFixed(1)} GiB/s`
  if (n >= 1024 ** 2) return `${(n / 1024 ** 2).toFixed(1)} MiB/s`
  if (n >= 1024) return `${(n / 1024).toFixed(0)} KiB/s`
  return `${Math.round(n)} B/s`
}

/** RPC up — used for start progress; ops handoff uses nodeReadyForOps. */
export function isOnline(status: StatusPayload | null): boolean {
  if (!status) return false
  if (status.connect?.ready) return true
  if (supportsIbdStep(status) && (status.sync?.ibd || status.rpc?.initialblockdownload)) {
    return false
  }
  return !!(status.rpc?.reachable || status.rpc?.http_ok)
}

export function stillSyncingInWizard(status: StatusPayload | null): boolean {
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
  return phase === 'run' || ns === 'syncing' || ns === 'sync' || cur === 'run' || cur === 'ibd'
}

export function heightProgressPct(height: {
  height: number
  network_height?: number | null
  behind?: number | null
  sync_pct?: number | null
} | null): number | null {
  if (!height) return null
  if (
    height.sync_pct != null &&
    Number.isFinite(height.sync_pct) &&
    height.sync_pct >= 0
  ) {
    return Math.max(0, Math.min(100, height.sync_pct))
  }
  const tip = height.network_height
  if (tip == null || tip <= 0) return null
  if (height.behind != null && height.behind <= 0) return 100
  const pct = (height.height / tip) * 100
  if (!Number.isFinite(pct)) return null
  return Math.max(0, Math.min(100, pct))
}

export function snapshotCanDownload(
  status: StatusPayload | null | undefined,
  idleAfterStop = false,
): boolean {
  const snap = status?.snapshot
  if (!snap || snap.ready) return false
  if (idleAfterStop) return true
  if (snapshotDownloadLive(status)) return false
  const phase = (snap.phase || '').toLowerCase()
  return !!snap.can_start || !!snap.aborted || phase === 'aborted'
}

export function snapshotCanStop(
  status: StatusPayload | null | undefined,
  idleAfterStop = false,
): boolean {
  if (idleAfterStop) return false
  if (status?.snapshot?.can_stop) return true
  return snapshotDownloadLive(status)
}

export function snapRunning(status: StatusPayload | null | undefined, idleAfterStop = false): boolean {
  if (idleAfterStop) return false
  return snapshotDownloadLive(status)
}
