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
  return out
    .toLowerCase()
    .replace(/\//g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
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
