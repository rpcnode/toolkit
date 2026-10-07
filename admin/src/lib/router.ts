export type Route =
  | { name: 'dashboard' }
  | { name: 'servers' }
  | { name: 'nodes' }
  | { name: 'node'; id: string }
  | { name: 'settings' }
  | { name: 'notifications' }
  | { name: 'clients' }
  | { name: 'networks' }
  | { name: 'login' }
  | { name: 'setup' }
  | { name: 'setupChannel' }
  | { name: 'install' }

const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

/** Panel node primary key (UUID v4). */
export function isNodeUUID(id: string): boolean {
  return UUID_RE.test((id || '').trim())
}

/** Normalize browser path to a panel SPA route. */
export function parseRoute(pathname: string): Route {
  let p = pathname || '/'
  if (p.length > 1 && p.endsWith('/')) p = p.slice(0, -1)

  // Legacy /status/* bookmarks
  if (p === '/status') return { name: 'dashboard' }
  if (p.startsWith('/status/')) {
    const rel = p.slice('/status'.length)
    if (rel === '/setup' || rel === '/install') return { name: 'install' }
    const env = rel.match(/^\/env\/([a-zA-Z0-9_-]+)$/)
    if (env) return { name: 'node', id: env[1] }
    return { name: 'dashboard' }
  }

  if (p === '/' || p === '/dashboard' || p === '/home') return { name: 'dashboard' }
  if (p === '/servers' || p === '/agents') return { name: 'servers' }
  if (p === '/nodes') return { name: 'nodes' }
  if (p === '/settings') return { name: 'settings' }
  if (p === '/notifications') return { name: 'notifications' }
  if (p === '/clients') return { name: 'clients' }
  if (p === '/networks') return { name: 'networks' }
  if (p === '/login') return { name: 'login' }
  if (p === '/setup' || p === '/setup-password') return { name: 'setup' }
  if (p === '/setup-channel') return { name: 'setupChannel' }
  if (p === '/install') return { name: 'install' }

  // UUID or legacy slug (bitcoin-mainnet) for one release.
  const node = p.match(/^\/nodes\/([a-zA-Z0-9_-]+)$/)
  if (node) return { name: 'node', id: node[1] }

  return { name: 'dashboard' }
}

export function hrefFor(route: Route): string {
  switch (route.name) {
    case 'dashboard':
      return '/'
    case 'servers':
      return '/servers'
    case 'nodes':
      return '/nodes'
    case 'settings':
      return '/settings'
    case 'notifications':
      return '/notifications'
    case 'clients':
      return '/clients'
    case 'networks':
      return '/networks'
    case 'login':
      return '/login'
    case 'setup':
      return '/setup'
    case 'setupChannel':
      return '/setup-channel'
    case 'install':
      return '/install'
    case 'node':
      return `/nodes/${encodeURIComponent(route.id)}`
  }
}

export function navigate(route: Route): void {
  const href = hrefFor(route)
  if (window.location.pathname !== href) {
    window.history.pushState({}, '', href)
  }
  window.dispatchEvent(new PopStateEvent('popstate'))
}

const KNOWN_ENVS = [
  'mainnet',
  'nile',
  'shasta',
  'testnet4',
  'signet',
  'regtest',
] as const

/**
 * Legacy slug → env (bitcoin-mainnet → mainnet).
 * UUID ids return '' — caller must use workload.env.
 */
export function nodeIdToEnv(id: string): string {
  const raw = (id || '').trim()
  if (!raw || isNodeUUID(raw)) return ''

  const prefixed = raw.match(/^(?:tron|bitcoin|btc|solana|ethereum|eth|polygon|matic|bsc)-(.+)$/i)
  if (prefixed) return prefixed[1]

  for (const e of KNOWN_ENVS) {
    if (raw === e) return e
  }

  return raw
}

/**
 * Legacy slug → network. UUID ids return ''.
 */
export function nodeIdToNetwork(id: string): string {
  const raw = (id || '').trim().toLowerCase()
  if (!raw || isNodeUUID(raw)) return ''
  const m = raw.match(/^(tron|bitcoin|btc|solana|ethereum|eth|polygon|matic|bsc)-/)
  if (!m) return ''
  if (m[1] === 'btc') return 'bitcoin'
  if (m[1] === 'eth') return 'ethereum'
  if (m[1] === 'matic') return 'polygon'
  return m[1]
}

/** @deprecated Node ids are UUIDs — prefer workload.id from the API. */
export function envToNodeId(env: string, network = ''): string {
  const e = (env || '').trim()
  const net = (network || '').trim().toLowerCase()
  if (!e) return net ? `${net}-mainnet` : ''
  if (isNodeUUID(e)) return e
  if (e.includes('-') && !KNOWN_ENVS.includes(e as (typeof KNOWN_ENVS)[number])) {
    return e
  }
  if (!net) return e
  return `${net}-${e}`
}
