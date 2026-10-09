/** Copy text on http://IP:8093 as well as localhost/HTTPS.
 *  `navigator.clipboard` is missing or rejects outside a secure context. */
export async function copyText(text: string): Promise<void> {
  const value = String(text ?? '')

  if (typeof navigator !== 'undefined' && window.isSecureContext && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(value)
      return
    } catch {
      /* fall through to execCommand */
    }
  }

  const el = document.createElement('textarea')
  el.value = value
  el.setAttribute('readonly', '')
  el.style.position = 'fixed'
  el.style.left = '-9999px'
  el.style.top = '0'
  document.body.appendChild(el)
  el.focus()
  el.select()
  el.setSelectionRange(0, el.value.length)
  let ok = false
  try {
    ok = document.execCommand('copy')
  } finally {
    document.body.removeChild(el)
  }
  if (!ok) throw new Error('Copy failed')
}
