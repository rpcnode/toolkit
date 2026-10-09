import { NextResponse } from 'next/server'
import {
  downloadHref,
  groupByNetwork,
  mirrorHref,
  networkHref,
  readCatalogue,
  siteOrigin,
} from '@/lib/catalogue'
import { envLabel, networkLabel, typeLabel } from '@/lib/seo'

export const dynamic = 'force-dynamic'

export async function GET()
{
  const origin = await siteOrigin()
  let body: string

  try
  {
    const cat = await readCatalogue()
    const networks = groupByNetwork(cat.mirrors)
    const lines = [
      '# RpcNode Snapshot CDN',
      '',
      '> Download mirrored blockchain node snapshot archives (TRON FullNode, LiteFullNode, and more).',
      '',
      `Origin: ${origin}`,
      '',
      '## Pages',
      '',
      `- [Home](${origin}/): network index — blockchain node snapshots download`,
      `- [Sitemap](${origin}/sitemap.xml)`,
      `- [Catalogue JSON](${origin}/api/catalogue)`,
      '',
      '## Networks',
      '',
    ]

    if (networks.length === 0)
    {
      lines.push('No mirrors published yet.')
    }
    else
    {
      for (const n of networks)
      {
        const label = networkLabel(n.network)
        lines.push(`### [${label} snapshots](${origin}${networkHref(n.network)})`)
        lines.push('')
        for (const m of n.mirrors)
        {
          const page = `${origin}${mirrorHref(m)}`
          const dl = downloadHref(m)
          const title = `${label} ${envLabel(m.env)} ${typeLabel(m.type || 'full')}`
          lines.push(
            `- ${title} — version ${m.version || 'unknown'}; [detail](${page})${dl ? `; [download](${origin}${dl})` : ''}`,
          )
        }
        lines.push('')
      }
    }

    if (cat.generated_at)
    {
      lines.push(`Catalogue generated_at: ${cat.generated_at}`)
    }

    body = lines.join('\n') + '\n'
  }
  catch (e: unknown)
  {
    const msg = e instanceof Error ? e.message : String(e)
    body = `# RpcNode Snapshot CDN\n\nCatalogue unavailable: ${msg}\n`
  }

  return new NextResponse(body, {
    headers: {
      'Content-Type': 'text/plain; charset=utf-8',
      'Cache-Control': 'public, max-age=60',
    },
  })
}
