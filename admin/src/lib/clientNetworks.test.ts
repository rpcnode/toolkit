import { describe, expect, it } from 'vitest'
import { networksWithClients } from './clientNetworks'
import type { NetworkInfo } from './networksCatalog'
import type { ClientRow } from '../api'

const catalog: NetworkInfo[] = [
  { id: 'ethereum', label: 'Ethereum', envs: ['mainnet', 'sepolia'] },
  { id: 'bitcoin', label: 'Bitcoin', envs: ['mainnet'] },
]

function row(network: string, env: string, status = 'ok'): ClientRow {
  return { network, env, program: 'x', pin: '1', status }
}

describe('networksWithClients', () => {
  it('keeps only catalog networks that have clients', () => {
    const got = networksWithClients(catalog, [
      row('ethereum', 'sepolia'),
      row('arb', 'mainnet'),
    ])
    expect(got.map((n) => n.id)).toEqual(['ethereum'])
    expect(got[0].envs).toEqual(['sepolia'])
  })

  it('does not append client-only networks missing from Networks', () => {
    const got = networksWithClients([{ id: 'bitcoin', label: 'Bitcoin', envs: ['mainnet'] }], [
      row('solana', 'mainnet'),
      row('bitcoin', 'mainnet'),
    ])
    expect(got.map((n) => n.id)).toEqual(['bitcoin'])
  })
})
