/** Semver-ish: local older than remote (same rules as panel agentVersionOutdated). */
export function agentVersionOutdated(local: string, remote: string): boolean {
  const a = local.trim().replace(/^v/i, '')
  const b = remote.trim().replace(/^v/i, '')
  if (!a || !b || a === b) return false
  const as = a.split('.')
  const bs = b.split('.')
  const n = Math.max(as.length, bs.length)
  for (let i = 0; i < n; i++) {
    const ai = parseInt(as[i] || '0', 10) || 0
    const bi = parseInt(bs[i] || '0', 10) || 0
    if (ai < bi) return true
    if (ai > bi) return false
  }
  return false
}
