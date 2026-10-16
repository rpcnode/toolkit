import { describe, expect, it } from 'vitest'
import { isL2Network, l1ParentSpec } from './l1Parent'

describe('l1ParentSpec', () => {
  it('keeps Ethereum RPC + beacon for the existing L2s', () => {
    for (const n of ['arb', 'base', 'robinhood', 'optimism']) {
      expect(isL2Network(n)).toBe(true)
      expect(l1ParentSpec(n)).toMatchObject({ chain: 'ethereum', beacon: true, ws: false })
    }
  })

  it('Xai is an Orbit L3: Arbitrum One parent, no beacon', () => {
    expect(isL2Network('xai')).toBe(true)
    expect(l1ParentSpec('XAI')).toMatchObject({ chain: 'arb', beacon: false, ws: false })
  })

  it('Starknet needs an Ethereum WebSocket and no beacon', () => {
    expect(isL2Network('starknet')).toBe(true)
    expect(l1ParentSpec('starknet')).toMatchObject({ chain: 'ethereum', beacon: false, ws: true })
  })

  it('other networks have no parent endpoint', () => {
    expect(isL2Network('solana')).toBe(false)
    expect(isL2Network(null)).toBe(false)
  })
})
