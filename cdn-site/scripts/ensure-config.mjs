#!/usr/bin/env node
/**
 * Before next start/dev: ensure SNAPSHOT_CDN_DIR is set and writable.
 * Writes /etc/rpcnode/rpcnode-cdn-site.env or ./rpcnode-cdn-site.env
 * and cdn-site/.env.local for Next.js.
 */
import { createInterface } from 'node:readline/promises'
import { stdin as input, stdout as output, stderr } from 'node:process'
import {
  accessSync,
  constants,
  existsSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const siteRoot = resolve(__dirname, '..')
const cwd = resolve(process.cwd())

function envFileCandidates()
{
  return [
    process.env.CDN_SITE_ENV_FILE?.trim(),
    '/etc/rpcnode/rpcnode-cdn-site.env',
    join(cwd, 'rpcnode-cdn-site.env'),
    join(siteRoot, 'rpcnode-cdn-site.env'),
  ].filter(Boolean)
}

function parseEnvFile(path)
{
  if (!existsSync(path)) return {}
  const out = {}
  for (const raw of readFileSync(path, 'utf8').split('\n'))
  {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const eq = line.indexOf('=')
    if (eq <= 0) continue
    const key = line.slice(0, eq).trim()
    const value = line.slice(eq + 1).trim().replace(/^["']|["']$/g, '')
    if (value) out[key] = value
  }
  return out
}

function loadMerged()
{
  const fromFiles = {}
  for (const path of envFileCandidates())
  {
    if (!path || !existsSync(path)) continue
    Object.assign(fromFiles, parseEnvFile(path))
  }
  return {
    snapshotDir: process.env.SNAPSHOT_CDN_DIR?.trim() || fromFiles.SNAPSHOT_CDN_DIR || '',
  }
}

function pickWritableEnvPath()
{
  if (process.env.CDN_SITE_ENV_FILE?.trim())
  {
    return process.env.CDN_SITE_ENV_FILE.trim()
  }
  try
  {
    mkdirSync('/etc/rpcnode', { recursive: true })
    accessSync('/etc/rpcnode', constants.W_OK)
    return '/etc/rpcnode/rpcnode-cdn-site.env'
  }
  catch
  {
    return join(cwd, 'rpcnode-cdn-site.env')
  }
}

function ensureDir(path)
{
  mkdirSync(path, { recursive: true })
  mkdirSync(join(path, 'snapshots'), { recursive: true })
  accessSync(path, constants.W_OK)
}

function writeEnv(path, snapshotDir)
{
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, `SNAPSHOT_CDN_DIR=${snapshotDir}\n`, { mode: 0o600 })
}

function isTty()
{
  return Boolean(input.isTTY && output.isTTY)
}

async function ask(question, defaultValue)
{
  const rl = createInterface({ input, output })
  try
  {
    const hint = defaultValue ? ` [${defaultValue}]` : ''
    const answer = (await rl.question(`${question}${hint}: `)).trim()
    return answer || defaultValue || ''
  }
  finally
  {
    rl.close()
  }
}

async function main()
{
  const merged = loadMerged()
  let snapshotDir = merged.snapshotDir

  if (!snapshotDir)
  {
    if (!isTty())
    {
      stderr.write(
        'ERROR: SNAPSHOT_CDN_DIR is not set — run once in a terminal to pick a folder ' +
          '(default: current directory), or set SNAPSHOT_CDN_DIR / rpcnode-cdn-site.env\n',
      )
      process.exit(1)
    }
    stderr.write('SNAPSHOT_CDN_DIR is not set — pick the snapshot root before starting the site.\n')
    stderr.write(`Default: ${cwd}\n`)
    snapshotDir = await ask('Snapshot directory (archives under <dir>/snapshots)', cwd)
    if (!snapshotDir)
    {
      stderr.write('ERROR: directory required\n')
      process.exit(1)
    }
    const envPath = pickWritableEnvPath()
    try
    {
      ensureDir(snapshotDir)
    }
    catch (e)
    {
      stderr.write(`ERROR: cannot use ${snapshotDir}: ${e.message}\n`)
      process.exit(1)
    }
    writeEnv(envPath, resolve(snapshotDir))
    stderr.write(`saved ${envPath}\n`)
    stderr.write(`snapshot dir → ${resolve(snapshotDir)}\n`)
  }
  else
  {
    try
    {
      ensureDir(snapshotDir)
    }
    catch (e)
    {
      stderr.write(`ERROR: SNAPSHOT_CDN_DIR invalid (${e.message})\n`)
      process.exit(1)
    }
  }

  const abs = resolve(snapshotDir)
  process.env.SNAPSHOT_CDN_DIR = abs

  writeFileSync(join(siteRoot, '.env.local'), `SNAPSHOT_CDN_DIR=${abs}\n`)
}

main().catch((e) =>
{
  stderr.write(`ERROR: ${e?.message || e}\n`)
  process.exit(1)
})
