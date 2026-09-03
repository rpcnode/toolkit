/** Mask host/IP in URLs for UI — full value stays available for copy. */

const IPV4_RE = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/

/** `185.44.207.104` → `185.*.*.104` (middle octets hidden; copy has the real value). */
export function maskHostname(host: string): string {
  const h = String(host || '').trim()
  if (!h) return h
  const m = h.match(IPV4_RE)
  if (m) return `${m[1]}.*.*.${m[4]}`
  // IPv6 / bracketed — collapse
  if (h.includes(':')) return '[*]'
  // Long hostname: keep TLD only
  const parts = h.split('.')
  if (parts.length >= 2) {
    return `….${parts[parts.length - 1]}`
  }
  return '…'
}

/**
 * Mask host in absolute URL; keep scheme + port.
 * Builds the string manually — URL.hostname rejects `*` and would leave the real IP.
 */
export function maskHostInURL(url: string): string {
  const raw = String(url || '').trim()
  if (!raw) return raw
  try {
    const u = new URL(raw)
    if (!u.hostname) return raw
    const maskedHost = maskHostname(u.hostname)
    const auth = u.username
      ? `${encodeURIComponent(u.username)}${u.password ? `:${encodeURIComponent(u.password)}` : ''}@`
      : ''
    const port = u.port ? `:${u.port}` : ''
    let path = `${u.pathname || ''}${u.search || ''}${u.hash || ''}`
    // Agent URLs are usually `http://host:port` with no path — drop URL()'s default `/`.
    if (path === '/' && !/https?:\/\/[^/?#]+\/./.test(raw) && !raw.endsWith('/')) {
      path = ''
    }
    return `${u.protocol}//${auth}${maskedHost}${port}${path}`
  } catch {
    // bare host:port
    const i = raw.lastIndexOf(':')
    if (i > 0 && /^\d+$/.test(raw.slice(i + 1))) {
      return `${maskHostname(raw.slice(0, i))}:${raw.slice(i + 1)}`
    }
    return maskHostname(raw)
  }
}
