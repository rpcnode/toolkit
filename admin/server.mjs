// Serves the built admin UI (dist/) and proxies /api, /install and /healthz to rpcnode-server —
// the same job nginx does in the Docker image (admin/nginx.docker.conf), without Docker.
// Run it under pm2 (ecosystem.config.cjs) or directly: `npm run build && npm start`.
//
//   ADMIN_PORT  listen port             (default 8093)
//   ADMIN_HOST  bind address            (default 0.0.0.0 — reachable from outside)
//   PANEL_URL   rpcnode-server origin   (default http://127.0.0.1:8094)
//   ADMIN_DIST  built UI directory      (default ./dist next to this file)
import { createReadStream } from 'node:fs'
import { stat } from 'node:fs/promises'
import http from 'node:http'
import https from 'node:https'
import { extname, join, normalize, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = fileURLToPath(new URL('.', import.meta.url))
const port = Number(process.env.ADMIN_PORT || 8093)
const host = process.env.ADMIN_HOST || '0.0.0.0'
const panel = new URL(process.env.PANEL_URL || 'http://127.0.0.1:8094')
const dist = resolve(process.env.ADMIN_DIST || join(here, 'dist'))

const PROXIED = ['/api/', '/install/']
const PROXIED_EXACT = ['/healthz']

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.webp': 'image/webp',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.map': 'application/json',
  '.txt': 'text/plain; charset=utf-8',
}

function isProxied(pathname) {
  return PROXIED_EXACT.includes(pathname) || PROXIED.some((p) => pathname.startsWith(p))
}

function proxy(req, res) {
  const client = panel.protocol === 'https:' ? https : http
  const headers = { ...req.headers }
  // Keep the browser's Host so the panel can build links that point back at this address.
  headers['x-forwarded-host'] = req.headers.host || ''
  headers['x-forwarded-proto'] = req.socket.encrypted ? 'https' : (req.headers['x-forwarded-proto'] || 'http')
  headers['x-forwarded-for'] = [req.headers['x-forwarded-for'], req.socket.remoteAddress].filter(Boolean).join(', ')
  headers['x-real-ip'] = req.socket.remoteAddress || ''
  const upstream = client.request(
    {
      protocol: panel.protocol,
      hostname: panel.hostname,
      port: panel.port || (panel.protocol === 'https:' ? 443 : 80),
      method: req.method,
      path: req.url,
      headers,
    },
    (up) => {
      res.writeHead(up.statusCode || 502, up.headers)
      up.pipe(res)
    },
  )
  upstream.on('error', (err) => {
    if (res.headersSent) {
      res.destroy()
      return
    }
    res.writeHead(502, { 'content-type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify({ ok: false, error: 'panel_unreachable', message: `${panel.origin}: ${err.message}` }))
  })
  req.on('aborted', () => upstream.destroy())
  req.pipe(upstream)
}

async function sendFile(req, res, file, immutable) {
  const info = await stat(file)
  const type = TYPES[extname(file).toLowerCase()] || 'application/octet-stream'
  res.writeHead(200, {
    'content-type': type,
    'content-length': info.size,
    // Hashed assets never change; index.html must always be re-checked so a rebuild is picked up.
    'cache-control': immutable ? 'public, max-age=31536000, immutable' : 'no-cache',
  })
  if (req.method === 'HEAD') {
    res.end()
    return
  }
  createReadStream(file).pipe(res)
}

async function serveStatic(req, res, pathname) {
  let rel
  try {
    rel = normalize(decodeURIComponent(pathname))
  } catch {
    res.writeHead(400).end('bad request')
    return
  }
  const file = join(dist, rel)
  if (file !== dist && !file.startsWith(dist + sep)) {
    res.writeHead(403).end('forbidden')
    return
  }
  try {
    const info = await stat(file)
    if (info.isFile()) {
      await sendFile(req, res, file, rel.startsWith(`${sep}assets${sep}`) || rel.startsWith(`assets${sep}`))
      return
    }
  } catch {
    /* fall through to the SPA entry */
  }
  // A missing file with an extension is a real 404; anything else is a client-side route.
  if (extname(pathname) && !pathname.endsWith('.html')) {
    res.writeHead(404).end('not found')
    return
  }
  try {
    await sendFile(req, res, join(dist, 'index.html'), false)
  } catch {
    res.writeHead(503, { 'content-type': 'text/plain; charset=utf-8' })
    res.end(`admin UI is not built: run "npm run build" in ${here}\n`)
  }
}

const server = http.createServer((req, res) => {
  const pathname = (req.url || '/').split('?')[0]
  if (isProxied(pathname)) {
    proxy(req, res)
    return
  }
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { allow: 'GET, HEAD' }).end()
    return
  }
  serveStatic(req, res, pathname).catch(() => {
    if (!res.headersSent) res.writeHead(500)
    res.end()
  })
})

server.listen(port, host, () => {
  console.log(`rpcnode-admin on http://${host}:${port}  ->  ${panel.origin}  (ui: ${dist})`)
})

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => server.close(() => process.exit(0)))
}
