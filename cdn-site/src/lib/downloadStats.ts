import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import { dirname, join, normalize, resolve, sep } from 'node:path'
import { snapshotCdnDir } from './catalogue'

export type DownloadStats = {
  count: number
  updated_at: string
}

function mirrorDirForRelativePath(relativePath: string): string
{
  const root = resolve(snapshotCdnDir(), 'snapshots')
  const parts = relativePath.split('/').filter(Boolean)
  if (parts.length < 4)
  {
    throw new Error('invalid snapshot path')
  }
  // network/env/type/filename → stats live in network/env/type/
  const dir = resolve(root, ...parts.slice(0, -1))
  const normalizedRoot = normalize(root + sep)
  const normalizedDir = normalize(dir + sep)
  if (!normalizedDir.startsWith(normalizedRoot))
  {
    throw new Error('path escapes snapshots root')
  }
  return dir
}

export function archiveAbsolutePath(relativePath: string): string
{
  const root = resolve(snapshotCdnDir(), 'snapshots')
  const parts = relativePath.split('/').filter(Boolean)
  if (parts.length < 4 || parts.some((p) => p === '..' || p.includes('\0')))
  {
    throw new Error('invalid snapshot path')
  }
  const file = resolve(root, ...parts)
  const normalizedRoot = normalize(root + sep)
  if (!normalize(file).startsWith(normalizedRoot))
  {
    throw new Error('path escapes snapshots root')
  }
  return file
}

export async function readDownloadCount(relativePath: string): Promise<number>
{
  try
  {
    const dir = mirrorDirForRelativePath(relativePath)
    const raw = await readFile(join(dir, 'downloads.json'), 'utf8')
    const data = JSON.parse(raw) as Partial<DownloadStats>
    const n = Number(data.count)
    return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0
  }
  catch
  {
    return 0
  }
}

export async function bumpDownloadCount(relativePath: string): Promise<number>
{
  const dir = mirrorDirForRelativePath(relativePath)
  await mkdir(dir, { recursive: true })
  const file = join(dir, 'downloads.json')
  const tmp = join(dir, 'downloads.json.tmp')

  let lastErr: unknown
  for (let attempt = 0; attempt < 25; attempt++)
  {
    try
    {
      let count = 0
      try
      {
        const raw = await readFile(file, 'utf8')
        const data = JSON.parse(raw) as Partial<DownloadStats>
        const n = Number(data.count)
        if (Number.isFinite(n) && n > 0) count = Math.floor(n)
      }
      catch
      {
        // first download
      }
      count += 1
      const body = JSON.stringify({
        count,
        updated_at: new Date().toISOString(),
      }) + '\n'
      await writeFile(tmp, body)
      await rename(tmp, file)
      return count
    }
    catch (e)
    {
      lastErr = e
      await new Promise((r) => setTimeout(r, 15 + attempt * 5))
    }
  }
  throw lastErr instanceof Error ? lastErr : new Error('failed to bump download count')
}
