function formatBytes(n) {
  if (n == null || Number.isNaN(n)) return 'size unknown'
  const u = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = Number(n)
  let i = 0
  while (v >= 1024 && i < u.length - 1) {
    v /= 1024
    i += 1
  }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${u[i]}`
}

function downloadHref(m) {
  if (m.path) {
    return `/snapshots/${m.path.split('/').map(encodeURIComponent).join('/')}`
  }
  if (m.filename && m.network && m.env) {
    const type = m.type || 'full'
    return `/snapshots/${encodeURIComponent(m.network)}/${encodeURIComponent(m.env)}/${encodeURIComponent(type)}/${encodeURIComponent(m.filename)}`
  }
  return null
}

async function load() {
  const status = document.getElementById('status')
  const list = document.getElementById('list')
  try {
    const res = await fetch('/snapshots/index.json', { cache: 'no-store' })
    if (!res.ok) {
      status.textContent = 'No catalogue yet — sync has not published index.json.'
      return
    }
    const data = await res.json()
    const mirrors = Array.isArray(data.mirrors) ? data.mirrors : []
    if (mirrors.length === 0) {
      status.innerHTML = '<div class="empty">No mirrors published yet.</div>'
      return
    }
    status.hidden = true
    list.hidden = false
    list.innerHTML = mirrors
      .map((m) => {
        const type = m.type || 'full'
        const title = `${m.network} · ${m.env} · ${type}`
        const meta = [m.date || m.version, formatBytes(m.size_bytes), m.version, m.updated_at]
          .filter(Boolean)
          .join(' · ')
        const href = downloadHref(m)
        const btn = href
          ? `<a class="dl" href="${href}">Download latest</a>`
          : '<span class="meta">no file</span>'
        return `<article class="row"><div><h2>${title}</h2><p class="meta">${meta}</p></div>${btn}</article>`
      })
      .join('')
  } catch (err) {
    status.textContent = `Could not load catalogue: ${err}`
  }
}

void load()
