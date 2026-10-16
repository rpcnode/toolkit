import { describe, expect, it } from 'vitest'
import { fallbackInstallGroups, installOptionLabel } from './InstallOptionsPicker'
import { isNodeTypeOptionGroup } from './NodeInstallWizard/steps'

describe('Cardano indexer option', () => {
  it('is offered on every Cardano env and nowhere else by accident', () => {
    for (const env of ['mainnet', 'preprod', 'preview']) {
      const groups = fallbackInstallGroups('cardano', env)
      expect(groups.map((g) => g.id)).toEqual(['indexer'])
    }
    expect(fallbackInstallGroups('cardano', 'sanchonet')).toEqual([])
  })

  it('defaults to ogmios (what the backend runs without the option) and has the four modes the backend parses', () => {
    const [group] = fallbackInstallGroups('cardano', 'mainnet')
    expect(group.default).toBe('ogmios')
    expect(group.choices.map((c) => c.id)).toEqual(['none', 'ogmios', 'dbsync', 'both'])
    expect(installOptionLabel([group], {})).toBe('Ogmios')
    expect(installOptionLabel([group], { indexer: 'both' })).toBe('Ogmios + db-sync')
  })

  it('is chosen on the Node type step, not the Snapshot step', () => {
    const [group] = fallbackInstallGroups('cardano', 'preview')
    expect(isNodeTypeOptionGroup(group)).toBe(true)
  })
})
