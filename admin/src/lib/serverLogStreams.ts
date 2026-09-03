import type { AgentLogStream } from '../api'

/** Prefer install → snapshot → errors for a node; tip/leaf agents last. */
export function streamRelevantToNode(
  s: AgentLogStream,
  network?: string,
  env?: string,
): boolean {
  if (!network) return true
  const id = (s.id || '').toLowerCase()
  const net = network.toLowerCase()
  const slug = `${net}-${(env || '').toLowerCase()}`
  if (id === 'host') return false // shared rpcnode.log retired
  if (id.startsWith('install')) return id === 'install' || id.includes(net)
  if (id.startsWith('snapshot')) return id.includes(net)
  if (id.startsWith('errors')) {
    return id === 'errors' || id.includes(net) || id.includes(slug)
  }
  if (
    id === 'rpcnode-api-agent' ||
    id === 'rpcnode-system-agent' ||
    id === 'rpcnode-agent-watchdog'
  ) {
    return true
  }
  return id.includes(slug) || id.includes(net)
}

function rank(id: string, network?: string): number {
  const net = (network || '').toLowerCase()
  const low = id.toLowerCase()
  if (net && low === `install-${net}`) return 0
  if (low.startsWith('install-')) return 1
  if (low.startsWith('snapshot-')) return 2
  if (low.startsWith('errors-')) return 3
  if (low === 'errors') return 4
  if (low.startsWith('rpcnode-api-agent-') || low.startsWith('rpcnode-system-agent-')) {
    return 5
  }
  if (low === 'rpcnode-api-agent' || low === 'rpcnode-system-agent') return 6
  if (low === 'rpcnode-agent-watchdog') return 7
  return 8
}

export function sortLogStreams(
  streams: AgentLogStream[],
  network?: string,
): AgentLogStream[] {
  return [...streams].sort((a, b) => {
    const d = rank(a.id || '', network) - rank(b.id || '', network)
    if (d !== 0) return d
    return (a.label || a.id || '').localeCompare(b.label || b.id || '')
  })
}

export function pickDefaultLogStream(
  streams: AgentLogStream[],
  network?: string,
  preferred?: string,
): string {
  if (preferred && streams.some((s) => s.id === preferred)) return preferred
  const net = (network || '').toLowerCase()
  if (net) {
    const install = streams.find((s) => (s.id || '').toLowerCase() === `install-${net}`)
    if (install?.id) return install.id
  }
  const firstInstall = streams.find((s) => (s.id || '').startsWith('install-'))
  if (firstInstall?.id) return firstInstall.id
  const snap = streams.find((s) => (s.id || '').startsWith('snapshot-'))
  if (snap?.id) return snap.id
  const err = streams.find((s) => (s.id || '').startsWith('errors-'))
  if (err?.id) return err.id
  return streams[0]?.id || ''
}
