import dayjs from 'dayjs'
import type { MetricPoint } from '../types'

export function healthColor(h?: string): string {
  switch ((h || '').toLowerCase()) {
    case 'ok':
    case 'healthy':
    case 'active':
    case 'suitable':
      return 'teal'
    case 'setup':
    case 'degraded':
    case 'maintenance':
    case 'warn':
      return 'yellow'
    default:
      return 'red'
  }
}

export function pct(raw: string | number | undefined): number {
  const n = typeof raw === 'number' ? raw : parseFloat(String(raw ?? '').replace('%', ''))
  if (Number.isNaN(n)) return 0
  return Math.max(0, Math.min(100, n))
}

/** Sync label: 0.017% stays visible (do not toFixed(1) → 0.0%). */
export function formatSyncPct(progress: number): string {
  if (!Number.isFinite(progress) || progress <= 0) return '0%'
  if (progress >= 99.95) return '100%'
  if (progress < 1) return `${progress.toFixed(3)}%`
  if (progress < 10) return `${progress.toFixed(1)}%`

  return `${Math.round(progress)}%`
}

export function parseSyncPctFromDetail(detail?: string | null): number | null {
  const m = String(detail || '').match(/(\d+(?:\.\d+)?)\s*%/)
  if (!m) return null
  const n = Number(m[1])
  if (!Number.isFinite(n)) return null

  return Math.max(0, Math.min(100, n))
}

/** aria2 `[#id 777GiB/1,742GiB(44%)]` — last % wins. */
/** Agave `solana_file_download` / "snapshot download 28.0%". Last match wins. */
export function parseSolanaDownloadPctFromText(text?: string | null): number | null {
  const s = String(text || '')
  if (!s) return null
  let last: number | null = null
  const re = /downloaded\s+\d+\s+bytes\s+([0-9.]+)%|snapshot download\s+([0-9.]+)%/gi
  let m: RegExpExecArray | null
  while ((m = re.exec(s)) !== null) {
    const n = Number(m[1] || m[2])
    if (Number.isFinite(n) && n > 0) last = Math.max(0, Math.min(100, n))
  }
  return last
}

export function parseAria2PctFromText(text?: string | null): number | null {
  const s = String(text || '')
  if (!s) return null
  const re = /\((\d+(?:\.\d+)?)%\)/g
  let last: number | null = null
  let m: RegExpExecArray | null
  while ((m = re.exec(s)) !== null) {
    const n = Number(m[1])
    if (Number.isFinite(n)) last = Math.max(0, Math.min(100, n))
  }
  return last
}

export function chartSeries(points: MetricPoint[] | undefined, key: string) {
  if (!points?.length) return []
  return points.map((p) => ({
    time: dayjs.unix(p.t).format('HH:mm:ss'),
    [key]: Number(p.v.toFixed(2)),
  }))
}

/** Align RX/TX host net samples onto one AreaChart series. */
export function chartNetSeries(rx?: MetricPoint[], tx?: MetricPoint[]) {
  const n = Math.max(rx?.length || 0, tx?.length || 0)
  if (!n) return []
  const out: Array<{ time: string; rx: number; tx: number }> = []
  for (let i = 0; i < n; i++) {
    const a = rx?.[i] as MetricPoint & { T?: number; V?: number } | undefined
    const b = tx?.[i] as MetricPoint & { T?: number; V?: number } | undefined
    const t = a?.t ?? a?.T ?? b?.t ?? b?.T
    if (t == null || Number.isNaN(Number(t))) continue
    const rv = a?.v ?? a?.V ?? 0
    const tv = b?.v ?? b?.V ?? 0
    out.push({
      time: dayjs.unix(Number(t)).format('HH:mm:ss'),
      rx: Number(Number(rv).toFixed(2)),
      tx: Number(Number(tv).toFixed(2)),
    })
  }
  return out
}

export function num(v: unknown, digits = 1): string {
  if (v == null || v === '') return '—'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  return n.toFixed(digits)
}

/** Canonical client_version (matches system-agent): lowercase, no slashes. */
export function formatClientVersion(raw: unknown): string {
  let s = String(raw ?? '').trim()
  if (!s) return ''
  const low0 = s.toLowerCase()
  // Shell / PATH noise must never render as client version (TON bootstrap).
  if (
    low0.includes('command not found') ||
    low0.startsWith('bash:') ||
    low0.startsWith('sh:') ||
    low0.includes('no such file')
  ) {
    return ''
  }
  if (s.includes('·')) {
    return s
      .split('·')
      .map((p) => formatClientVersion(p.trim()))
      .filter(Boolean)
      .join(' · ')
  }
  while (s.startsWith('/') || s.endsWith('/')) {
    if (s.startsWith('/')) s = s.slice(1)
    if (s.endsWith('/')) s = s.slice(0, -1)
    s = s.trim()
  }
  let out = ''
  const colon = s.lastIndexOf(':')
  if (colon > 0 && !s.slice(0, colon).includes('://')) {
    const name = s.slice(0, colon).trim()
    const ver = s.slice(colon + 1).trim().replace(/^v/i, '')
    if (name && looksLikeClientVersionToken(ver)) {
      out = clientVersionNameRedundant(name) ? ver : `${name} ${ver}`
    }
  }
  if (!out && s.includes('/')) {
    const [head, verRaw] = s.split('/')
    const ver = (verRaw || '').trim().replace(/^v/i, '')
    if (head?.trim() && looksLikeClientVersionToken(ver)) out = `${head.trim()} ${ver}`
  }
  if (!out) {
    const low = s.toLowerCase()
    for (const p of ['greatvoyage-', 'java-tron-', 'tron-']) {
      if (low.startsWith(p)) {
        out = s.slice(p.length).trim().replace(/^v/i, '')
        break
      }
    }
  }
  if (!out) out = s
  out = out
    .toLowerCase()
    .replace(/\//g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return shortenFormattedClientVersion(out)
}

/** "geth 1.17.4-stable-36a7dc72" → "geth 1.17.4"; keep 1.8.0-alpha / 29.1.0(eb32.0). */
function shortenFormattedClientVersion(s: string): string {
  const fields = s.split(/\s+/).filter(Boolean)
  if (!fields.length) return s
  for (let i = fields.length - 1; i >= 0; i--) {
    if (looksLikeClientVersionToken(fields[i]) || fields[i].includes('(')) {
      fields[i] = shortReleaseVersion(fields[i])
      return fields.slice(0, i + 1).join(' ')
    }
  }
  return s
}

function shortReleaseVersion(s: string): string {
  let t = s.replace(/^v/i, '').trim()
  if (!t) return ''
  let end = 0
  let dots = 0
  for (let i = 0; i < t.length; i++) {
    const c = t[i]
    if (c >= '0' && c <= '9') {
      end = i + 1
      continue
    }
    if (c === '.') {
      dots++
      end = i + 1
      continue
    }
    break
  }
  if (dots < 1 || end === 0) return t
  if (t[end] === '(') return t
  const core = t.slice(0, end)
  if (end >= t.length) return core
  let rest = t.slice(end).replace(/^-/, '')
  const sp = rest.search(/[ \t]/)
  if (sp >= 0) rest = rest.slice(0, sp)
  const low = rest.toLowerCase()
  if (low.startsWith('alpha') || low.startsWith('beta') || low.startsWith('rc') || low.startsWith('preview')) {
    return `${core}-${rest}`
  }
  return core
}

/** Product UA that restates the network label — show version only (BCH/LTC/…). */
function clientVersionNameRedundant(name: string): boolean {
  const n = name.toLowerCase().trim().replace(/\s+/g, ' ')
  switch (n) {
    case 'bitcoin cash node':
    case 'bitcoin cash':
    case 'bchn':
    case 'litecoin core':
    case 'litecoin':
    case 'dogecoin core':
    case 'dogecoin':
    case 'bitcoin core':
      return true
    default:
      return n.includes('bitcoin cash')
  }
}

function looksLikeClientVersionToken(s: string): boolean {
  let t = s.replace(/^v/i, '')
  if (!t) return false
  // BCHN-style: 29.1.0(EB32.0)
  const paren = t.indexOf('(')
  if (paren > 0 && t.endsWith(')') && paren < t.length - 1) {
    t = t.slice(0, paren)
  }
  return /^\d+\.\d+[\w.+_-]*$/.test(t)
}

/** Host disk IOPS (reads or writes completed / s). */
export function fmtIOPS(v: unknown): string {
  if (v == null || v === '') return '—'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  if (n >= 10000) return `${(n / 1000).toFixed(1)}k`
  if (n >= 10) return n.toFixed(0)
  return n.toFixed(1)
}

/** Host disk throughput (MB/s, 1e6 bytes). */
export function fmtDiskMBs(v: unknown): string {
  if (v == null || v === '') return '—'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  if (n >= 1000) return `${(n / 1000).toFixed(2)} GB/s`
  if (n >= 10) return `${n.toFixed(1)} MB/s`
  return `${n.toFixed(2)} MB/s`
}

/** Align two metric series (disk read/write IOPS, etc.). */
export function chartPairSeries(
  a?: MetricPoint[],
  b?: MetricPoint[],
  aKey = 'a',
  bKey = 'b',
): Array<Record<string, string | number>> {
  const n = Math.max(a?.length || 0, b?.length || 0)
  if (!n) return []
  const out: Array<Record<string, string | number>> = []
  for (let i = 0; i < n; i++) {
    const pa = a?.[i] as MetricPoint & { T?: number; V?: number } | undefined
    const pb = b?.[i] as MetricPoint & { T?: number; V?: number } | undefined
    const t = pa?.t ?? pa?.T ?? pb?.t ?? pb?.T
    if (t == null || Number.isNaN(Number(t))) continue
    out.push({
      time: dayjs.unix(Number(t)).format('HH:mm:ss'),
      [aKey]: Number(Number(pa?.v ?? pa?.V ?? 0).toFixed(2)),
      [bKey]: Number(Number(pb?.v ?? pb?.V ?? 0).toFixed(2)),
    })
  }
  return out
}

/** Human host network rate (Mbps preferred). */
export function fmtMbps(v: unknown): string {
  if (v == null || v === '') return '—'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  if (n >= 1000) return `${(n / 1000).toFixed(2)} Gbps`
  if (n >= 10) return `${n.toFixed(1)} Mbps`
  return `${n.toFixed(2)} Mbps`
}

/** Cumulative byte counters (IPAccounting) → GiB/TiB. */
export function fmtBytesGiB(v: unknown): string {
  if (v == null || v === '') return '—'
  const n = Number(v)
  if (!Number.isFinite(n) || n < 0) return '—'
  const gib = n / (1024 * 1024 * 1024)
  return formatStorageGiB(gib, gib >= 10 ? 1 : 2)
}

/** Compact free space for disk tabs: 2.9T / 421G. */
export function fmtDiskFree(gb?: number): string {
  if (gb == null || !Number.isFinite(gb) || gb < 0) return ''
  if (gb >= 1024) return `${(gb / 1024).toFixed(1)}T`
  if (gb >= 10) return `${gb.toFixed(0)}G`
  return `${gb.toFixed(1)}G`
}

/** Format GiB value as GiB or TiB for compact UI labels. */
export function formatStorageGiB(gb: number, digits = 0): string {
  if (!Number.isFinite(gb) || gb < 0) return '—'
  if (gb >= 1024) {
    const tib = gb / 1024
    const d = tib >= 10 ? 1 : Math.max(digits, 1)
    return `${tib.toFixed(d)} TiB`
  }
  return `${gb.toFixed(digits)} GiB`
}

/**
 * Host disk free + total from panel server_metrics (disk_*_gb).
 * Missing totals → "—".
 */
export function formatDiskFreeTotal(m?: {
  disk_used_gb?: number
  disk_total_gb?: number
} | null): string {
  const total = m?.disk_total_gb
  if (total == null || !Number.isFinite(total) || total <= 0) return '—'
  const used = m?.disk_used_gb
  const free =
    used != null && Number.isFinite(used) ? Math.max(0, total - used) : null
  if (free == null) return `total ${formatStorageGiB(total)}`
  return `free ${formatStorageGiB(free)} · total ${formatStorageGiB(total)}`
}

function shortMountLabel(target: string): string {
  const t = String(target || '').trim() || '/'
  if (t === '/') return '/'
  const segs = t.replace(/\/+$/, '').split('/').filter(Boolean)
  if (segs[0] === 'data' && segs[1]) return segs.slice(0, 2).join('/')
  if (segs.length <= 2) return t
  return '/' + segs.slice(-2).join('/')
}

type HostMountFree = {
  target?: string
  avail_bytes?: number
  size_bytes?: number
  preferred?: boolean
}

type HostDiskFree = {
  name?: string
  mountpoint?: string
  fsavail_bytes?: number
  fssize_bytes?: number
  size_bytes?: number
  preferred?: boolean
}

function asFinite(v: unknown): number {
  if (typeof v === 'number' && Number.isFinite(v)) return v
  const n = parseFloat(String(v ?? ''))
  return Number.isFinite(n) ? n : 0
}

function mountFreeBytes(m: HostMountFree): number {
  const avail = asFinite(m.avail_bytes)
  if (avail > 0) return avail
  return asFinite(m.size_bytes)
}

/** Free space on every usable host mount (Add node server picker). */
export function formatHostMountsFree(mounts?: HostMountFree[] | null): string {
  if (!mounts?.length) return ''
  const rows = mounts
    .filter((m) => m.target && mountFreeBytes(m) > 0)
    .sort((a, b) => {
      const ap = a.preferred ? 0 : 1
      const bp = b.preferred ? 0 : 1
      if (ap !== bp) return ap - bp
      const aRoot = a.target === '/' ? 1 : 0
      const bRoot = b.target === '/' ? 1 : 0
      if (aRoot !== bRoot) return aRoot - bRoot
      return mountFreeBytes(b) - mountFreeBytes(a)
    })
  if (!rows.length) return ''
  return rows
    .map((m) => {
      const gb = mountFreeBytes(m) / (1024 * 1024 * 1024)
      return `${shortMountLabel(m.target || '/')} ${formatStorageGiB(gb)}`
    })
    .join(' · ')
}

function diskFreeBytes(d: HostDiskFree): number {
  const avail = asFinite(d.fsavail_bytes)
  if (avail > 0) return avail
  const fs = asFinite(d.fssize_bytes)
  if (fs > 0) return fs
  return asFinite(d.size_bytes)
}

function formatHostDisksFree(disks?: HostDiskFree[] | null): string {
  if (!disks?.length) return ''
  const rows = disks
    .filter((d) => (d.mountpoint || d.name) && diskFreeBytes(d) > 0)
    .sort((a, b) => {
      const ap = a.preferred ? 0 : 1
      const bp = b.preferred ? 0 : 1
      if (ap !== bp) return ap - bp
      const aRoot = a.mountpoint === '/' ? 1 : 0
      const bRoot = b.mountpoint === '/' ? 1 : 0
      if (aRoot !== bRoot) return aRoot - bRoot
      return diskFreeBytes(b) - diskFreeBytes(a)
    })
  if (!rows.length) return ''
  return rows
    .map((d) => {
      const gb = diskFreeBytes(d) / (1024 * 1024 * 1024)
      const label = d.mountpoint
        ? shortMountLabel(d.mountpoint)
        : String(d.name || '').trim() || 'disk'
      return `${label} ${formatStorageGiB(gb)}`
    })
    .join(' · ')
}

function hasNonRootMount(mounts?: HostMountFree[] | null): boolean {
  return !!mounts?.some(
    (m) =>
      !!m.target &&
      m.target !== '/' &&
      !m.target.startsWith('/boot') &&
      mountFreeBytes(m) > 0,
  )
}

/** Add node server line: every data mount / disk, not OS disk_*_gb alone. */
export function formatServerDisks(
  mounts?: HostMountFree[] | null,
  disks?: HostDiskFree[] | null,
): string {
  const mountLine = formatHostMountsFree(mounts)
  if (mountLine && hasNonRootMount(mounts)) return mountLine
  const diskLine = formatHostDisksFree(disks)
  if (diskLine) return diskLine
  return mountLine
}

/** Node added / install / synced / updated timestamps (RFC3339 from panel SQLite). */
export function formatNodeWhen(iso?: string | null, compact = false): string {
  const s = String(iso || '').trim()
  if (!s) return '—'
  const d = dayjs(s)
  if (!d.isValid() || d.year() < 2000) return '—'
  if (compact) {
    return d.year() === dayjs().year() ? d.format('D MMM HH:mm') : d.format('D MMM YYYY')
  }
  return d.format('D MMM YYYY HH:mm')
}
