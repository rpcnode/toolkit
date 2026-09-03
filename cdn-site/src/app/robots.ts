import type { MetadataRoute } from 'next'
import { siteOrigin } from '@/lib/catalogue'

export const dynamic = 'force-dynamic'

export default async function robots(): Promise<MetadataRoute.Robots>
{
  const origin = await siteOrigin()
  return {
    rules: {
      userAgent: '*',
      allow: '/',
    },
    sitemap: `${origin}/sitemap.xml`,
  }
}
