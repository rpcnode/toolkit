/** Published compose ports — admin UI 8093, rpcnode-server 8094, CDN 8095. */
export const ADMIN_LISTEN_PORT = 8093
export const SERVER_LISTEN_PORT = 8094
export const CDN_LISTEN_PORT = 8095

export function isLoopbackHost(host: string): boolean {
  const h = (host || '').trim().toLowerCase().replace(/^\[|\]$/g, '')
  return h === 'localhost' || h === '127.0.0.1' || h === '::1' || h === '0.0.0.0'
}

export function pageHost(): string {
  if (typeof window === 'undefined') return ''
  return (window.location.hostname || '').trim()
}

export function originHost(raw: string): string {
  try {
    return new URL(raw).hostname
  } catch {
    return ''
  }
}

export function advertisedOrigin(host: string, port: number): string {
  const h = host.trim()
  if (!h) return `http://<host>:${port}`
  const proto =
    typeof window !== 'undefined' && window.location.protocol === 'https:' ? 'https' : 'http'
  return `${proto}://${h}:${port}`
}

/** Host other containers / node agents must use — never 127.0.0.1 from Docker. */
export function suggestedAdvertisedHost(savedOrigin?: string, panelPreset?: string): string {
  const fromSaved = originHost(savedOrigin || '')
  if (fromSaved && !isLoopbackHost(fromSaved)) return fromSaved
  const fromPanel = originHost(panelPreset || '')
  if (fromPanel && !isLoopbackHost(fromPanel)) return fromPanel
  const fromPage = pageHost()
  if (fromPage && !isLoopbackHost(fromPage)) return fromPage
  return ''
}
