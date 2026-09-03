import type { Metadata } from 'next'
import Link from 'next/link'
import {
  groupByNetwork,
  networkHref,
  readCatalogue,
  siteOrigin,
} from '@/lib/catalogue'
import { homeSeo, networkLabel, pageMetadata } from '@/lib/seo'

export const dynamic = 'force-dynamic'

export async function generateMetadata(): Promise<Metadata>
{
  const cat = await readCatalogue()
  const networks = groupByNetwork(cat.mirrors).map((n) => n.network)
  return pageMetadata(homeSeo(networks), '/', { absoluteTitle: true })
}

export default async function HomePage()
{
  const cat = await readCatalogue()
  const origin = await siteOrigin()
  const networks = groupByNetwork(cat.mirrors)
  const seo = homeSeo(networks.map((n) => n.network))

  const jsonLd = {
    '@context': 'https://schema.org',
    '@type': 'CollectionPage',
    name: seo.title,
    description: seo.description,
    url: origin + '/',
    hasPart: networks.map((n) => ({
      '@type': 'CollectionPage',
      name: `${networkLabel(n.network)} node snapshots`,
      url: `${origin}${networkHref(n.network)}`,
    })),
  }

  return (
    <main>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <section className="hero">
        <p className="eyebrow">public mirror index</p>
        <h1 className="hero-brand">
          blockchain node <span>snapshots</span>
        </h1>
        <p className="lede">
          Pick a network to download the latest mirrored snapshot. An empty index
          means the sync daemon has not published a mirror yet.
        </p>
      </section>

      {networks.length === 0 ? (
        <div className="empty">No mirrors published yet.</div>
      ) : (
        <>
          <div className="list-head" aria-hidden="true">
            <span>#</span>
            <span>network</span>
            <span>open</span>
          </div>
          <div className="list">
            {networks.map((n, i) =>
            {
              const envs = [...new Set(n.mirrors.map((m) => m.env))].join(', ')
              return (
                <article className="row" key={n.network}>
                  <span className="idx">{String(i + 1).padStart(2, '0')}</span>
                  <div>
                    <Link className="title" href={networkHref(n.network)}>
                      <h2>{networkLabel(n.network)}</h2>
                    </Link>
                    <p className="meta">
                      {n.mirrors.length} snapshot{n.mirrors.length === 1 ? '' : 's'}
                      {envs ? ` · ${envs}` : ''}
                    </p>
                  </div>
                  <div className="actions">
                    <Link className="dl" href={networkHref(n.network)}>
                      open
                    </Link>
                  </div>
                </article>
              )
            })}
          </div>
        </>
      )}
      {cat.generated_at ? (
        <p className="gen">Catalogue generated at {cat.generated_at}</p>
      ) : null}
    </main>
  )
}
