import type { Metadata } from 'next'
import type { MirrorItem } from './catalogue'

/** Human labels for search-relevant titles (not raw ids). */
const NETWORK_LABELS: Record<string, string> = {
  tron: 'TRON',
  bitcoin: 'Bitcoin',
  ethereum: 'Ethereum',
  solana: 'Solana',
}

const ENV_LABELS: Record<string, string> = {
  mainnet: 'Mainnet',
  nile: 'Nile Testnet',
  shasta: 'Shasta Testnet',
  testnet: 'Testnet',
  testnet4: 'Testnet4',
  signet: 'Signet',
  regtest: 'Regtest',
}

const TYPE_LABELS: Record<string, string> = {
  full: 'Full Node',
  lite: 'Lite FullNode',
  archive: 'Archive',
  internal_tx: 'Internal TX',
}

export const SITE_TITLE_DEFAULT =
  'Blockchain Node Snapshots — Free Download Mirror | RpcNode CDN'

export const SITE_TITLE_TEMPLATE = '%s | RpcNode Snapshot CDN'

export const SITE_DESCRIPTION_DEFAULT =
  'Download mirrored blockchain node snapshots (TRON FullNode, LiteFullNode and more). Fast Range-capable HTTP mirrors by RpcNode Snapshot CDN.'

export function networkLabel(network: string): string
{
  const key = network.trim().toLowerCase()
  return NETWORK_LABELS[key] || network.trim() || 'Network'
}

export function envLabel(env: string): string
{
  const key = env.trim().toLowerCase()
  return ENV_LABELS[key] || env.trim() || 'Environment'
}

export function typeLabel(type: string): string
{
  const key = (type || 'full').trim().toLowerCase()
  return TYPE_LABELS[key] || type.trim() || 'Full'
}

export function homeSeo(networks: string[]): PageSeo
{
  const names = networks.map(networkLabel)
  const list =
    names.length === 0
      ? 'blockchain networks'
      : names.length <= 4
        ? names.join(', ')
        : `${names.slice(0, 3).join(', ')} and more`
  return {
    title: SITE_TITLE_DEFAULT,
    description: `Browse and download the latest node snapshot archives for ${list}. Official mirrors republished on RpcNode Snapshot CDN with Range support.`,
    keywords: [
      'blockchain node snapshot',
      'download node snapshot',
      'rpc node snapshot',
      'fullnode snapshot mirror',
      ...names.map((n) => `${n} snapshot download`),
    ],
  }
}

export function networkSeo(
  network: string,
  mirrors: Pick<MirrorItem, 'env' | 'type'>[],
): PageSeo
{
  const name = networkLabel(network)
  const envs = [...new Set(mirrors.map((m) => envLabel(m.env)))]
  const types = [...new Set(mirrors.map((m) => typeLabel(m.type || 'full')))]
  const envPart = envs.length ? envs.join(', ') : 'all environments'
  const typePart = types.length ? types.join(', ') : 'node'
  return {
    title: `Download ${name} Node Snapshot (${envPart})`,
    description: `Download ${name} ${typePart} snapshot archives (${envPart}) from RpcNode Snapshot CDN. Mirrored official tarballs for fast bootstrap with HTTP Range.`,
    keywords: [
      `${name} snapshot`,
      `${name} node snapshot download`,
      `${name} fullnode snapshot`,
      ...envs.map((e) => `${name} ${e} snapshot`),
      ...types.map((t) => `${name} ${t} download`),
      'blockchain snapshot mirror',
    ],
  }
}

export function mirrorSeo(m: Pick<MirrorItem, 'network' | 'env' | 'type' | 'version' | 'date' | 'filename'>): PageSeo
{
  const name = networkLabel(m.network)
  const env = envLabel(m.env)
  const kind = typeLabel(m.type || 'full')
  const ver = (m.version || m.date || '').trim()
  const title = ver
    ? `Download ${name} ${env} ${kind} Snapshot ${ver}`
    : `Download ${name} ${env} ${kind} Snapshot`
  const file = m.filename?.trim()
  const description = [
    `Download the ${name} ${env} ${kind} node snapshot`,
    ver ? `(${ver})` : null,
    file ? `— ${file}` : null,
    'from RpcNode Snapshot CDN. Official archive mirror, Range-capable HTTP.',
  ]
    .filter(Boolean)
    .join(' ')
  return {
    title,
    description,
    keywords: [
      `${name} ${env} snapshot`,
      `${name} ${kind} download`,
      `${name} ${env} ${kind}`,
      'node snapshot tarball',
      'fullnode bootstrap snapshot',
    ],
  }
}

export type PageSeo = {
  title: string
  description: string
  keywords: string[]
}

/** Shared Next.js Metadata fields so every page emits description / OG / robots meta tags. */
export function pageMetadata(seo: PageSeo, path: string, opts?: { absoluteTitle?: boolean }): Metadata
{
  const title = opts?.absoluteTitle ? { absolute: seo.title } : seo.title
  return {
    title,
    description: seo.description,
    keywords: seo.keywords,
    authors: [{ name: 'RpcNode', url: 'https://rpcnode.dev/' }],
    creator: 'RpcNode',
    publisher: 'RpcNode Snapshot CDN',
    category: 'technology',
    robots: {
      index: true,
      follow: true,
      googleBot: {
        index: true,
        follow: true,
      },
    },
    alternates: {
      canonical: path,
    },
    openGraph: {
      title: seo.title,
      description: seo.description,
      url: path,
      siteName: 'RpcNode Snapshot CDN',
      locale: 'en_US',
      type: 'website',
    },
    twitter: {
      card: 'summary',
      title: seo.title,
      description: seo.description,
    },
    other: {
      'theme-color': '#070b0a',
    },
  }
}
