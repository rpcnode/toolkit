/** Mainnet genesis ledger (XRPL). Testnet / altnet start at 1. */
export const XRPL_GENESIS_MAINNET = 32570

export function xrplGenesisForEnv(env?: string | null): number {
  const e = (env || '').toLowerCase().trim()
  if (e === 'testnet' || e === 'devnet' || e === 'altnet') return 1
  return XRPL_GENESIS_MAINNET
}

export function parseXrplComplete(raw?: string | null): { lo: number; hi: number } | null {
  const s = String(raw || '').trim()
  if (!s || s === 'empty' || s === '<nil>') return null
  const m = s.match(/^(\d+)\s*-\s*(\d+)/)
  if (!m) return null
  const lo = Number(m[1])
  const hi = Number(m[2])
  if (!Number.isFinite(lo) || !Number.isFinite(hi) || lo <= 0 || hi <= 0) return null
  return { lo, hi }
}

/** History toward genesis. Tip-only window (~20k) → ~0.01%, not 100. */
export function xrplHistoryPct(lo: number, seq: number, genesis: number): number {
  if (seq <= 0 || lo <= 0) return 0
  if (genesis <= 0) genesis = 1
  if (lo <= genesis && seq > genesis) return 99.9
  if (seq <= genesis) return 0.1
  const span = seq - genesis
  if (span <= 0) return 0.1
  const pct = ((seq - lo) / span) * 100
  const out = Math.round(pct * 1000) / 1000
  if (out < 0.001) return 0.001
  if (out >= 100) return 99.9
  return out
}

export function xrplTipLive(serverState?: string | null, detail?: string | null): boolean {
  const st = (serverState || '').toLowerCase()
  if (st === 'full' || st === 'proposing' || st === 'validating') return true
  return /state\s*=\s*full\b/i.test(String(detail || ''))
}
