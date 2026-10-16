import { describe, expect, it } from 'vitest'
import { fallbackInstallGroups, opstackRestoreOptions } from '../components/InstallOptionsPicker'
import { officialDocsUrl } from './networkDocs'
import { isNoSnapshotNetwork } from './network'
import { OPSTACK_NETWORK_IDS, isOpL2Network, isOpstackNetwork, opstackNeedsSnapshotUrl } from './opstack'
import { prodPresetFor } from './prodPresets'
import { CHAIN_PATH_PREFIXES } from './networkPaths'
import { MULTI_DISK_NETWORKS } from '../components/NodeInstallWizard/utils'
import { isL2Network } from '../components/NodeParentChain'

describe('opstack family', () => {
  it('knows exactly the shipped networks', () => {
    expect([...OPSTACK_NETWORK_IDS]).toEqual(['unichain', 'ink', 'soneium', 'worldchain', 'lisk', 'mode', 'zora'])
    expect(isOpstackNetwork('Unichain')).toBe(true)
    expect(isOpstackNetwork('optimism')).toBe(false)
    expect(isOpL2Network('optimism')).toBe(true)
    expect(isOpL2Network('celo')).toBe(false)
  })

  it('is wired into every screen that hard-coded optimism', () => {
    for (const id of OPSTACK_NETWORK_IDS) {
      expect(isNoSnapshotNetwork(id), `${id} no snapshot step`).toBe(true)
      expect(MULTI_DISK_NETWORKS.has(id), `${id} disk layout`).toBe(true)
      expect(isL2Network(id), `${id} parent chain picker`).toBe(true)
      expect(CHAIN_PATH_PREFIXES[id], `${id} paths`).toBeTruthy()
      expect(officialDocsUrl(id).startsWith('https://'), `${id} docs`).toBe(true)
      expect(officialDocsUrl(id), `${id} docs not the fallback`).not.toBe('https://rpcnode.dev')
      expect(prodPresetFor(id)?.flags?.['rpc.max-connections'], `${id} preset`).toBe('3000')
    }
  })

  it('offers node type + initial data for mainnet and sepolia, nothing for other envs', () => {
    for (const id of OPSTACK_NETWORK_IDS) {
      const groups = fallbackInstallGroups(id, 'mainnet')
      expect(groups.map((g) => g.id)).toEqual(['node', 'restore'])
      expect(fallbackInstallGroups(id, 'sepolia').map((g) => g.id)).toEqual(['node', 'restore'])
      expect(fallbackInstallGroups(id, 'testnet')).toEqual([])
    }
    // the wizard classes groups named snapshot* as the snapshot-type step, which this family does not have
    for (const g of fallbackInstallGroups('unichain', 'mainnet')) {
      expect(g.id.startsWith('snapshot')).toBe(false)
    }
  })

  it('asks for a snapshot URL where no public snapshot exists and labels the third-party ones', () => {
    for (const id of ['mode', 'zora']) {
      expect(opstackNeedsSnapshotUrl(id)).toBe(true)
      const g = opstackRestoreOptions(id, 'mainnet')
      expect(g.textInput?.id).toBe('snapshot_url')
      expect(g.textInput?.label).toBe('Snapshot URL')
      expect(g.hint).toContain('WEEKS')
    }
    expect(opstackRestoreOptions('soneium', 'sepolia').hint).toContain('No public snapshot')
    expect(opstackRestoreOptions('soneium', 'mainnet').hint).toContain('THIRD-PARTY')
    expect(opstackRestoreOptions('unichain', 'sepolia').hint).toContain('no checksum')
    expect(opstackRestoreOptions('lisk', 'mainnet').hint).toContain('sha256')
    expect(opstackRestoreOptions('worldchain', 'mainnet').hint).toContain('Official')
    expect(opstackRestoreOptions('lisk', 'mainnet').textInput?.label).toBe('Snapshot URL (optional)')
  })
})
