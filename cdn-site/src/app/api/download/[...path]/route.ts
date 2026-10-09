import { access } from 'node:fs/promises'
import { NextRequest, NextResponse } from 'next/server'
import { archiveAbsolutePath, bumpDownloadCount } from '@/lib/downloadStats'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type Params = { path: string[] }

function relativeFromParams(path: string[] | undefined): string | null
{
  if (!path || path.length < 4)
  {
    return null
  }
  if (path.some((p) => !p || p === '.' || p === '..' || p.includes('/') || p.includes('\\')))
  {
    return null
  }
  return path.join('/')
}

export async function GET(
  _req: NextRequest,
  ctx: { params: Promise<Params> },
)
{
  const { path } = await ctx.params
  const relative = relativeFromParams(path)
  if (!relative)
  {
    return NextResponse.json({ error: 'invalid_path' }, { status: 400 })
  }

  let abs: string
  try
  {
    abs = archiveAbsolutePath(relative)
  }
  catch
  {
    return NextResponse.json({ error: 'invalid_path' }, { status: 400 })
  }

  try
  {
    await access(abs)
  }
  catch
  {
    return NextResponse.json({ error: 'not_found' }, { status: 404 })
  }

  try
  {
    await bumpDownloadCount(relative)
  }
  catch (e)
  {
    const message = e instanceof Error ? e.message : String(e)
    return NextResponse.json({ error: 'counter_failed', message }, { status: 500 })
  }

  const target = `/snapshots/${relative.split('/').map(encodeURIComponent).join('/')}`
  // Relative Location so nginx (:8095) keeps serving the archive, not Next (:7090).
  return new NextResponse(null, {
    status: 302,
    headers: {
      Location: target,
      'Cache-Control': 'no-store',
    },
  })
}
