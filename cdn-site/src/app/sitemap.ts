import type { MetadataRoute } from 'next'
import { groupByNetwork, mirrorSlug, networkHref, readCatalogue, siteOrigin } from '@/lib/catalogue'

export const dynamic = 'force-dynamic'

export default async function sitemap(): Promise<MetadataRoute.Sitemap>
{
  const origin = await siteOrigin()
  const entries: MetadataRoute.Sitemap = [
    {
      url: `${origin}/`,
      changeFrequency: 'hourly',
      priority: 1,
    },
  ]

  try
  {
    const cat = await readCatalogue()
    for (const n of groupByNetwork(cat.mirrors))
    {
      entries.push({
        url: `${origin}${networkHref(n.network)}`,
        changeFrequency: 'hourly',
        priority: 0.9,
      })
    }
    for (const m of cat.mirrors)
    {
      const slug = mirrorSlug(m)
      entries.push({
        url: `${origin}/mirrors/${encodeURIComponent(slug.network)}/${encodeURIComponent(slug.env)}/${encodeURIComponent(slug.type)}`,
        lastModified: m.updated_at || undefined,
        changeFrequency: 'daily',
        priority: 0.8,
      })
    }
  }
  catch
  {
    // Catalogue missing during build or misconfig — home only.
  }

  return entries
}
