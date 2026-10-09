import type { Metadata } from 'next'
import Link from 'next/link'
import { notFound } from 'next/navigation'
import {
  downloadHref,
  findMirror,
  formatBytes,
  networkHref,
  siteOrigin,
} from '@/lib/catalogue'
import { envLabel, mirrorSeo, networkLabel, pageMetadata, typeLabel } from '@/lib/seo'

export const dynamic = 'force-dynamic'

type Params = { network: string; env: string; type: string }

export async function generateMetadata({
  params,
}: {
  params: Promise<Params>
}): Promise<Metadata>
{
  const { network, env, type } = await params
  const m = await findMirror(network, env, type)
  if (!m)
  {
    return {
      title: 'Snapshot not found',
      description: 'This snapshot mirror is not in the RpcNode Snapshot CDN catalogue.',
      robots: { index: false, follow: true },
    }
  }
  const path = `/mirrors/${encodeURIComponent(network)}/${encodeURIComponent(env)}/${encodeURIComponent(type)}`
  return pageMetadata(mirrorSeo(m), path)
}

export default async function MirrorDetailPage({
  params,
}: {
  params: Promise<Params>
})
{
  const { network, env, type } = await params
  const m = await findMirror(network, env, type)
  if (!m)
  {
    notFound()
  }

  const origin = await siteOrigin()
  const href = downloadHref(m)
  const absoluteDownload = href ? origin + href : undefined
  const pageUrl = `${origin}/mirrors/${encodeURIComponent(network)}/${encodeURIComponent(env)}/${encodeURIComponent(type)}`
  const seo = mirrorSeo(m)
  const name = networkLabel(m.network)
  const envName = envLabel(m.env)
  const kind = typeLabel(m.type || 'full')

  const jsonLd = {
    '@context': 'https://schema.org',
    '@type': 'SoftwareApplication',
    name: `${name} ${envName} ${kind} snapshot`,
    softwareVersion: m.version || undefined,
    operatingSystem: 'Linux',
    applicationCategory: 'DeveloperApplication',
    url: pageUrl,
    downloadUrl: absoluteDownload,
    fileSize: m.size_bytes != null ? String(m.size_bytes) : undefined,
    dateModified: m.updated_at || undefined,
    description: seo.description,
  }

  const fields: [string, string][] = [
    ['Network', name],
    ['Environment', envName],
    ['Type', kind],
    ['Version', m.version || '—'],
    ['Date', m.date || '—'],
    ['Size', formatBytes(m.size_bytes)],
    ['Filename', m.filename || '—'],
    ['Downloads', m.download_count && m.download_count > 0 ? String(m.download_count) : '—'],
    ['Updated', m.updated_at || '—'],
  ]

  return (
    <main className="detail">
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <p className="crumb">
        <Link href="/">Index</Link>
        {' / '}
        <Link href={networkHref(m.network)}>{name}</Link>
        {' / '}
        {envName} · {kind}
      </p>
      <p className="eyebrow">mirror detail</p>
      <h1 className="page-title">
        download {name} {envName} {kind} snapshot
      </h1>
      <p className="lede">
        Official archive mirrored locally. Prefer Range-capable HTTP clients for
        large downloads.
      </p>
      <dl className="spec">
        {fields.map(([label, value]) => (
          <div className="spec-row" key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      <div className="actions">
        {href ? (
          <a className="dl" href={href}>
            download archive
          </a>
        ) : (
          <span className="meta">no file path in catalogue</span>
        )}
        <Link className="ghost" href={networkHref(m.network)}>
          all {name} snapshots
        </Link>
      </div>
    </main>
  )
}
