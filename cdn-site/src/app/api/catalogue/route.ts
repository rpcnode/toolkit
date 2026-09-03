import { NextResponse } from 'next/server'
import { readCatalogue } from '@/lib/catalogue'

export const dynamic = 'force-dynamic'

export async function GET()
{
  try
  {
    const cat = await readCatalogue()
    return NextResponse.json(cat, {
      headers: {
        'Cache-Control': 'public, max-age=30',
      },
    })
  }
  catch (e: unknown)
  {
    const message = e instanceof Error ? e.message : String(e)
    return NextResponse.json({ error: message }, { status: 503 })
  }
}
