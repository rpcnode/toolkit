import type { Metadata } from 'next'
import Link from 'next/link'
import { notFound } from 'next/navigation'
import {
  downloadHref,
  formatBytes,
  mirrorHref,
  mirrorsForNetwork,
  networkHref,
  siteOrigin,
} from '@/lib/catalogue'
import { envLabel, networkLabel, networkSeo, pageMetadata, typeLabel } from '@/lib/seo'

export const dynamic = 'force-dynamic'

type Params = { network: string }

export async function generateMetadata({
  params,
}: {
  params: Promise<Params>
}): Promise<Metadata>
{
  const { network } = await params
  const data = await mirrorsForNetwork(network)
  if (!data)
  {
    return {
      title: 'Network not found',
      description: 'This network is not in the RpcNode Snapshot CDN catalogue.',
      robots: { index: false, follow: true },
    }
  }
  const display = data.mirrors[0]?.network || network
  return pageMetadata(networkSeo(display, data.mirrors), networkHref(display))
}

export default async function NetworkPage({
  params,
}: {
  params: Promise<Params>
})
{
  const { network } = await params
  const data = await mirrorsForNetwork(network)
  if (!data)
  {
    notFound()
  }

  const origin = await siteOrigin()
  const displayName = data.mirrors[0]?.network || network
  const label = networkLabel(displayName)
  const seo = networkSeo(displayName, data.mirrors)
  const pageUrl = `${origin}${networkHref(displayName)}`

  const jsonLd = {
    '@context': 'https://schema.org',
    '@type': 'CollectionPage',
    name: seo.title,
    description: seo.description,
    url: pageUrl,
    hasPart: data.mirrors.map((m) =>
    {
      const href = downloadHref(m)
      return {
        '@type': 'SoftwareApplication',
        name: `${label} ${envLabel(m.env)} ${typeLabel(m.type || 'full')} snapshot`,
        softwareVersion: m.version || undefined,
        url: `${origin}${mirrorHref(m)}`,
        downloadUrl: href ? origin + href : undefined,
        fileSize: m.size_bytes != null ? String(m.size_bytes) : undefined,
      }
    }),
  }

  return (
    <main>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <p className="crumb">
        <Link href="/">Index</Link>
        {' / '}
        {label}
      </p>
      <section className="hero">
        <p className="eyebrow">network snapshots</p>
        <h1 className="page-title">download {label} node snapshot</h1>
        <p className="lede">
          Latest mirrored {label} archives for bootstrap. Prefer Range-capable HTTP
          clients for large files.
        </p>
      </section>

      {data.mirrors.length === 0 ? (
        <div className="empty">No snapshots published for this network yet.</div>
      ) : (
        <>
          <div className="list-head" aria-hidden="true">
            <span>#</span>
            <span>env · type</span>
            <span>download</span>
          </div>
          <div className="list">
            {data.mirrors.map((m, i) =>
            {
              const href = downloadHref(m)
              const meta = [
                m.date || m.version,
                formatBytes(m.size_bytes),
                m.version,
                m.download_count && m.download_count > 0 ? `${m.download_count} downloads` : null,
                m.updated_at,
              ]
                .filter(Boolean)
                .join(' · ')
              return (
                <article className="row" key={`${m.env}/${m.type}/${m.filename}`}>
                  <span className="idx">{String(i + 1).padStart(2, '0')}</span>
                  <div>
                    <Link className="title" href={mirrorHref(m)}>
                      <h2>
                        {envLabel(m.env)} · {typeLabel(m.type || 'full')}
                      </h2>
                    </Link>
                    <p className="meta">{meta}</p>
                    {m.filename ? <p className="meta">{m.filename}</p> : null}
                  </div>
                  <div className="actions">
                    {href ? (
                      <a className="dl" href={href}>
                        download
                      </a>
                    ) : (
                      <span className="meta">no file</span>
                    )}
                  </div>
                </article>
              )
            })}
          </div>
        </>
      )}
      {data.generated_at ? (
        <p className="gen">Catalogue generated at {data.generated_at}</p>
      ) : null}
    </main>
  )
}
