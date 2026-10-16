import { describe, expect, it } from 'vitest'
import {
  POLKADOT_INDEXER_OPTIONS,
  POLKADOT_SNAPSHOT_OPTIONS,
  chooseOption,
  choiceBlocked,
  fallbackInstallGroups,
  installOptionLabel,
} from './InstallOptionsPicker'
import { isNodeTypeOptionGroup, isSnapshotOptionGroup } from './NodeInstallWizard/steps'

describe('Polkadot indexer option', () => {
  const groups = fallbackInstallGroups('polkadot', 'mainnet')

  it('is offered on every Polkadot env: node type on the Snapshot step, indexer on the Node type step', () => {
    for (const env of ['mainnet', 'paseo', 'westend']) {
      expect(fallbackInstallGroups('polkadot', env).map((g) => g.id)).toEqual(['snapshot', 'indexer'])
    }
    expect(isSnapshotOptionGroup(POLKADOT_SNAPSHOT_OPTIONS)).toBe(true)
    expect(isNodeTypeOptionGroup(POLKADOT_INDEXER_OPTIONS)).toBe(true)
  })

  it('has the three values the backend parses, none by default', () => {
    expect(POLKADOT_INDEXER_OPTIONS.default).toBe('none')
    expect(POLKADOT_INDEXER_OPTIONS.choices.map((c) => c.id)).toEqual(['none', 'rest-api', 'history'])
    expect(installOptionLabel(groups, {})).toBe('Pruned · None')
    expect(installOptionLabel(groups, { snapshot: 'archive', indexer: 'history' })).toBe('Archive · History')
  })

  it('shows the start block field for the history indexer only, under the id the backend reads', () => {
    expect(POLKADOT_INDEXER_OPTIONS.textInput?.id).toBe('indexer_start_block')
    expect(POLKADOT_INDEXER_OPTIONS.textInput?.whenChoice).toBe('history')
  })

  it('choosing history also chooses the archive node type, and pruned is then not selectable', () => {
    const next = chooseOption(groups, {}, 'indexer', 'history')
    expect(next).toEqual({ indexer: 'history', snapshot: 'archive' })
    const pruned = POLKADOT_SNAPSHOT_OPTIONS.choices.find((c) => c.id === 'pruned')!
    const archive = POLKADOT_SNAPSHOT_OPTIONS.choices.find((c) => c.id === 'archive')!
    expect(choiceBlocked(pruned, next, groups)).toBe(true)
    expect(choiceBlocked(archive, next, groups)).toBe(false)
  })

  it('leaves the node type free for none and rest-api', () => {
    const pruned = POLKADOT_SNAPSHOT_OPTIONS.choices.find((c) => c.id === 'pruned')!
    expect(choiceBlocked(pruned, {}, groups)).toBe(false)
    expect(choiceBlocked(pruned, chooseOption(groups, {}, 'indexer', 'rest-api'), groups)).toBe(false)
    expect(chooseOption(groups, { snapshot: 'archive' }, 'indexer', 'none')).toEqual({ snapshot: 'archive', indexer: 'none' })
  })

  it('does not change anything for networks without these rules', () => {
    const cardano = fallbackInstallGroups('cardano', 'mainnet')
    expect(chooseOption(cardano, { a: 'b' }, 'indexer', 'dbsync')).toEqual({ a: 'b', indexer: 'dbsync' })
  })
})
