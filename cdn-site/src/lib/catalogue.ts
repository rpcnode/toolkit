import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { headers } from 'next/headers'
import { readDownloadCount } from './downloadStats'

export type MirrorItem = {
  network: string
  env: string
  type: string
  version: string
  date: string
  size_bytes: number | null
  filename: string
  path: string
  updated_at: string | null
  download_count?: number
}

export type Catalogue = {
  generated_at: string
  mirrors: MirrorItem[]
}

export function snapshotCdnDir(): string
{
  const dir = process.env.SNAPSHOT_CDN_DIR?.trim()
  if (!dir)
  {
    throw new Error('SNAPSHOT_CDN_DIR is not set')
  }
  return dir
}

/** Public origin for sitemap / canonical / JSON-LD — from the request Host. */
export async function siteOrigin(): Promise<string>
{
  try
  {
    const h = await headers()
    const host = h.get('x-forwarded-host') || h.get('host')
    if (host)
    {
      const proto = h.get('x-forwarded-proto') || 'http'
      return `${proto}://${host}`.replace(/\/$/, '')
    }
  }
  catch
  {
    // Outside a request (build) — fallback for metadataBase.
  }
  return 'http://127.0.0.1:7090'
}

/** Counted download URL (bumps downloads.json, then redirects to /snapshots/…). */
export function downloadHref(m: Pick<MirrorItem, 'path' | 'filename' | 'network' | 'env' | 'type'>): string | null
{
  if (m.path)
  {
    return `/api/download/${m.path.split('/').map(encodeURIComponent).join('/')}`
  }
  if (m.filename && m.network && m.env)
  {
    const type = m.type || 'full'
    return `/api/download/${encodeURIComponent(m.network)}/${encodeURIComponent(m.env)}/${encodeURIComponent(type)}/${encodeURIComponent(m.filename)}`
  }
  return null
}

/** Direct nginx file URL (no counter) — used after redirect. */
export function snapshotFileHref(m: Pick<MirrorItem, 'path' | 'filename' | 'network' | 'env' | 'type'>): string | null
{
  if (m.path)
  {
    return `/snapshots/${m.path.split('/').map(encodeURIComponent).join('/')}`
  }
  if (m.filename && m.network && m.env)
  {
    const type = m.type || 'full'
    return `/snapshots/${encodeURIComponent(m.network)}/${encodeURIComponent(m.env)}/${encodeURIComponent(type)}/${encodeURIComponent(m.filename)}`
  }
  return null
}

export function formatBytes(n: number | null | undefined): string
{
  if (n == null || Number.isNaN(n)) return 'size unknown'
  const u = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = Number(n)
  let i = 0
  while (v >= 1024 && i < u.length - 1)
  {
    v /= 1024
    i += 1
  }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${u[i]}`
}

export function mirrorTitle(m: Pick<MirrorItem, 'network' | 'env' | 'type'>): string
{
  return `${m.network} · ${m.env} · ${m.type || 'full'}`
}

export function networkHref(network: string): string
{
  return `/networks/${encodeURIComponent(network)}`
}

export function mirrorHref(m: Pick<MirrorItem, 'network' | 'env' | 'type'>): string
{
  const slug = mirrorSlug(m)
  return `/mirrors/${encodeURIComponent(slug.network)}/${encodeURIComponent(slug.env)}/${encodeURIComponent(slug.type)}`
}

export function mirrorSlug(m: Pick<MirrorItem, 'network' | 'env' | 'type'>): {
  network: string
  env: string
  type: string
}
{
  return {
    network: m.network,
    env: m.env,
    type: m.type || 'full',
  }
}

export type NetworkSummary = {
  network: string
  mirrors: MirrorItem[]
}

export function groupByNetwork(mirrors: MirrorItem[]): NetworkSummary[]
{
  const map = new Map<string, MirrorItem[]>()
  for (const m of mirrors)
  {
    const key = m.network.trim() || 'unknown'
    const list = map.get(key) ?? []
    list.push(m)
    map.set(key, list)
  }
  return [...map.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([network, items]) => ({
      network,
      mirrors: items.slice().sort((x, y) =>
      {
        const e = x.env.localeCompare(y.env)
        if (e !== 0) return e
        return (x.type || 'full').localeCompare(y.type || 'full')
      }),
    }))
}

export async function mirrorsForNetwork(network: string): Promise<{
  generated_at: string
  mirrors: MirrorItem[]
} | null>
{
  const cat = await readCatalogue()
  const want = network.trim().toLowerCase()
  if (!want)
  {
    return null
  }
  const mirrors = cat.mirrors.filter((m) => m.network.toLowerCase() === want)
  if (mirrors.length === 0)
  {
    return null
  }
  return {
    generated_at: cat.generated_at,
    mirrors: mirrors.slice().sort((x, y) =>
    {
      const e = x.env.localeCompare(y.env)
      if (e !== 0) return e
      return (x.type || 'full').localeCompare(y.type || 'full')
    }),
  }
}

export async function readCatalogue(): Promise<Catalogue>
{
  const indexPath = join(snapshotCdnDir(), 'snapshots', 'index.json')
  try
  {
    const raw = await readFile(indexPath, 'utf8')
    const data = JSON.parse(raw) as Partial<Catalogue>
    const mirrors = Array.isArray(data.mirrors)
      ? await Promise.all(
          data.mirrors.map(async (m) =>
          {
            const path = String(m.path ?? '')
            const download_count = path
              ? await readDownloadCount(path)
              : 0
            return {
              network: String(m.network ?? ''),
              env: String(m.env ?? ''),
              type: String(m.type || 'full'),
              version: String(m.version ?? ''),
              date: String(m.date ?? ''),
              size_bytes: m.size_bytes == null ? null : Number(m.size_bytes),
              filename: String(m.filename ?? ''),
              path,
              updated_at: m.updated_at == null ? null : String(m.updated_at),
              download_count,
            }
          }),
        )
      : []
    return {
      generated_at: String(data.generated_at ?? ''),
      mirrors,
    }
  }
  catch (e: unknown)
  {
    const err = e as NodeJS.ErrnoException
    if (err?.code === 'ENOENT')
    {
      return { generated_at: '', mirrors: [] }
    }
    throw e
  }
}

export async function findMirror(
  network: string,
  env: string,
  type: string,
): Promise<MirrorItem | null>
{
  const cat = await readCatalogue()
  const wantType = type || 'full'
  return (
    cat.mirrors.find(
      (m) => m.network === network && m.env === env && (m.type || 'full') === wantType,
    ) ?? null
  )
}
