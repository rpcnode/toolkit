import { Radio, Stack, Text, UnstyledButton } from '@mantine/core'

export type InstallOptionChoice = {
  id: string
  title: string
  hint?: string
  snapshot_url?: string
}

export type InstallOptionGroup = {
  id: string
  label: string
  hint?: string
  default?: string
  choices: InstallOptionChoice[]
}

export const TRON_MAINNET_SNAPSHOT_OPTIONS: InstallOptionGroup = {
  id: 'snapshot',
  label: 'Snapshot',
  hint: 'Official TRON FullNode mirrors. Internal transactions and historical balances are different snapshots.',
  default: 'standard',
  choices: [
    {
      id: 'standard',
      title: 'Standard · no internal txs',
      hint: 'US Virginia. ~2.9 TB. Default. AML will not see historical contract internals.',
    },
    {
      id: 'internal_tx',
      title: 'Internal transactions',
      hint: 'Singapore. ~3.1 TB. For AML that must see contract internal calls (gettransactioninfobyid).',
    },
    {
      id: 'balance_history',
      title: 'Historical TRX balances',
      hint: 'US. ~3.6 TB. getaccountbalance at any past block. No historical internal txs.',
    },
  ],
}

export function parseInstallOptionGroups(raw: unknown): InstallOptionGroup[] {
  if (!Array.isArray(raw)) return []
  const out: InstallOptionGroup[] = []
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue
    const g = item as Record<string, unknown>
    const id = String(g.id || '').trim()
    const choicesRaw = Array.isArray(g.choices) ? g.choices : []
    const choices: InstallOptionChoice[] = []
    for (const c of choicesRaw) {
      if (!c || typeof c !== 'object') continue
      const ch = c as Record<string, unknown>
      const cid = String(ch.id || '').trim()
      if (!cid) continue
      choices.push({
        id: cid,
        title: String(ch.title || cid),
        hint: ch.hint ? String(ch.hint) : undefined,
        snapshot_url: ch.snapshot_url ? String(ch.snapshot_url) : undefined,
      })
    }
    if (!id || !choices.length) continue
    out.push({
      id,
      label: String(g.label || id),
      hint: g.hint ? String(g.hint) : undefined,
      default: g.default ? String(g.default) : undefined,
      choices,
    })
  }
  return out
}

export const BSC_SNAPSHOT_OPTIONS: InstallOptionGroup = {
  id: 'snapshot',
  label: 'Snapshot',
  hint: 'Official bnb-chain/bsc-snapshots (geth PBSS). Genesis full sync of 100M+ blocks is not practical.',
  default: 'pruned',
  choices: [
    {
      id: 'pruned',
      title: 'Pruned · pruneancient',
      hint: 'Official pruneancient (~1.7 TB mainnet / ~180 GB testnet). Matches gcmode=full + 2 TB plan. Default.',
    },
    {
      id: 'full',
      title: 'Full history',
      hint: 'Official full ancient (~6.6 TB mainnet / ~440 GB testnet). Needs ~8 TB free. Not the 2 TB product disk.',
    },
  ],
}

export const BASE_SNAPSHOT_OPTIONS: InstallOptionGroup = {
  id: 'snapshot',
  label: 'Snapshot',
  hint: 'Official V2 snapshots via base-reth-node download. A from-scratch sync takes days.',
  default: 'archive',
  choices: [
    {
      id: 'archive',
      title: 'Archive · full history',
      hint: 'Everything available, no pruning (~3711 GiB mainnet / ~956 GiB sepolia). Matches the archive unit. Default.',
    },
    {
      id: 'full',
      title: 'Full · reth full-node preset',
      hint: "reth's --full preset (~3145 GiB mainnet): state, headers and only the last 10064 blocks (~5-6 h on Base).",
    },
    {
      id: 'minimal',
      title: 'Minimal · state + headers',
      hint: 'Smallest set needed to boot (~677 GiB mainnet / ~283 GiB sepolia). No historical data.',
    },
  ],
}

export const XRPL_HISTORY_OPTIONS: InstallOptionGroup = {
  id: 'xrpl_history',
  label: 'History to install',
  hint: 'How much ledger history xrpld will keep after Install. Full history has no public snapshot.',
  default: 'weeks',
  choices: [
    { id: 'stock', title: 'Stock · ~2 hours', hint: '2 000 ledgers — default xrpld window. Smallest disk, no archive RPC.' },
    { id: 'day', title: '1 day', hint: '25 000 ledgers. Typical public RPC day window.' },
    { id: 'weeks', title: '2 weeks', hint: '300 000 ledgers. Default for a new install. Disk ×2 of the window.' },
    { id: 'full', title: 'Full history', hint: 'Genesis → tip (ledger 32 570 on mainnet). No snapshot. Official archive ~39 TiB — not a VPS.' },
  ],
}

export const SOLANA_NODE_OPTIONS: InstallOptionGroup = {
  id: 'node',
  label: 'Node type',
  hint: 'Full RPC keeps a bounded ledger. Archive drops --limit-ledger-size and grows toward full history (hundreds of TiB on mainnet).',
  default: 'full',
  choices: [
    {
      id: 'full',
      title: 'Full RPC · bounded ledger',
      hint: 'Product default. Cluster snapshot + --limit-ledger-size. Live RPC, not genesis→tip history.',
    },
    {
      id: 'archive',
      title: 'Archive · no ledger limit',
      hint: 'Omits --limit-ledger-size. Same cluster snapshot bootstrap; disk plan ~400 TiB mainnet. Not BigTable.',
    },
  ],
}

export const ETHEREUM_NODE_OPTIONS: InstallOptionGroup = {
  id: 'node',
  label: 'Node type',
  hint: 'Geth --gcmode. Full = snap+gcmode full. Archive = full sync + gcmode archive (multi-TiB).',
  default: 'full',
  choices: [
    {
      id: 'full',
      title: 'Full · snap + gcmode full',
      hint: 'Product default. Not archive RPC.',
    },
    {
      id: 'archive',
      title: 'Archive · gcmode archive',
      hint: 'Geth --syncmode full --gcmode archive. Mainnet ~14 TiB floor.',
    },
  ],
}

export const POLYGON_NODE_OPTIONS: InstallOptionGroup = {
  id: 'node',
  label: 'Node type',
  hint: 'Bor profile. Full = sentry. Archive = archive config (multi-TiB mainnet).',
  default: 'full',
  choices: [
    {
      id: 'full',
      title: 'Full · sentry',
      hint: 'Product default. Live tip RPC, not deep history.',
    },
    {
      id: 'archive',
      title: 'Archive · bor archive',
      hint: 'Official archive profile. Mainnet ~16 TiB floor.',
    },
  ],
}

export const ARB_SNAPSHOT_OPTIONS: InstallOptionGroup = {
  id: 'snapshot',
  label: 'Snapshot',
  hint: 'Nitro init. Pruned = --init.latest=pruned. Archive = PathDB archive-path + caching.archive.',
  default: 'pruned',
  choices: [
    {
      id: 'pruned',
      title: 'Pruned · full node',
      hint: 'Product default. Live tip, not deep history.',
    },
    {
      id: 'archive',
      title: 'Archive · PathDB',
      hint: 'Official archive-path (~3.7 TB One). Not Classic pre-Nitro.',
    },
  ],
}

export const TON_HISTORY_OPTIONS: InstallOptionGroup = {
  id: 'history',
  label: 'History',
  hint: 'MyTonCtrl liteserver dump (~30d) vs archive (≥12 TiB).',
  default: 'dump',
  choices: [
    {
      id: 'dump',
      title: 'Dump · ~30 days',
      hint: 'install.sh -m liteserver -d. Product default.',
    },
    {
      id: 'archive',
      title: 'Archive · full history',
      hint: 'install.sh -m liteserver --archive. Official ≥12–20 TiB.',
    },
  ],
}

export const APTOS_BOOTSTRAP_OPTIONS: InstallOptionGroup = {
  id: 'bootstrap',
  label: 'Bootstrap',
  hint: 'Genesis catch-up vs public backup restore. Pruners stay off (full history). Fast sync is never offered.',
  default: 'genesis',
  choices: [
    {
      id: 'genesis',
      title: 'Genesis · sync from genesis',
      hint: 'ExecuteOrApplyFromGenesis. Slow, no third-party DB.',
    },
    {
      id: 'restore',
      title: 'Restore · public backup',
      hint: 'aptos node bootstrap-db from public backups. Needs Aptos CLI.',
    },
  ],
}

export function fallbackInstallGroups(network?: string, env?: string): InstallOptionGroup[] {
  const net = (network || '').toLowerCase()
  const e = (env || '').toLowerCase()
  if (net === 'tron' && e === 'mainnet') {
    return [TRON_MAINNET_SNAPSHOT_OPTIONS]
  }
  if (net === 'bsc' && (e === 'mainnet' || e === 'testnet')) {
    return [BSC_SNAPSHOT_OPTIONS]
  }
  if (net === 'base' && (e === 'mainnet' || e === 'sepolia')) {
    return [BASE_SNAPSHOT_OPTIONS]
  }
  if (net === 'xrpl' && (e === 'mainnet' || e === 'testnet')) {
    return [XRPL_HISTORY_OPTIONS]
  }
  if (net === 'solana' && (e === 'mainnet' || e === 'testnet' || e === 'devnet')) {
    return [SOLANA_NODE_OPTIONS]
  }
  if (net === 'ethereum' && (e === 'mainnet' || e === 'sepolia' || e === 'hoodi')) {
    return [ETHEREUM_NODE_OPTIONS]
  }
  if (net === 'polygon' && (e === 'mainnet' || e === 'amoy')) {
    return [POLYGON_NODE_OPTIONS]
  }
  if (net === 'arb' && (e === 'mainnet' || e === 'sepolia')) {
    return [ARB_SNAPSHOT_OPTIONS]
  }
  if (net === 'ton' && (e === 'mainnet' || e === 'testnet')) {
    return [TON_HISTORY_OPTIONS]
  }
  if (net === 'aptos' && (e === 'mainnet' || e === 'testnet')) {
    return [APTOS_BOOTSTRAP_OPTIONS]
  }
  return []
}

export function installOptionLabel(groups: InstallOptionGroup[], selected: Record<string, string>): string {
  const parts: string[] = []
  for (const g of groups) {
    const id = selected[g.id] || g.default || ''
    const ch = g.choices.find((c) => c.id === id)
    if (ch) parts.push(ch.title.split('·')[0].trim())
  }
  return parts.join(' · ')
}

export function InstallOptionsPicker({
  groups,
  value,
  onChange,
  disabled,
}: {
  groups: InstallOptionGroup[]
  value: Record<string, string>
  onChange: (next: Record<string, string>) => void
  disabled?: boolean
}) {
  if (!groups.length) return null

  return (
    <Stack gap="md">
      {groups.map((g) => {
        const current = value[g.id] || g.default || g.choices[0]?.id || ''
        return (
          <Stack key={g.id} gap="xs">
            <Text size="sm" fw={600}>
              {g.label}
            </Text>
            {g.hint ? (
              <Text size="xs" c="dimmed">
                {g.hint}
              </Text>
            ) : null}
            <Radio.Group
              value={current}
              onChange={(v) => onChange({ ...value, [g.id]: v })}
            >
              <Stack gap={8}>
                {g.choices.map((m) => {
                  const selected = current === m.id
                  return (
                    <UnstyledButton
                      key={m.id}
                      disabled={disabled}
                      onClick={() => onChange({ ...value, [g.id]: m.id })}
                      p="sm"
                      style={{
                        borderRadius: 8,
                        border: `1px solid ${
                          selected ? 'var(--mantine-color-teal-6)' : 'var(--mantine-color-dark-4)'
                        }`,
                        background: selected ? 'var(--mantine-color-dark-6)' : 'transparent',
                        textAlign: 'left',
                      }}
                    >
                      <Radio
                        value={m.id}
                        label={
                          <Stack gap={2}>
                            <Text size="sm" fw={600}>
                              {m.title}
                            </Text>
                            {m.hint ? (
                              <Text size="xs" c="dimmed" style={{ whiteSpace: 'normal' }}>
                                {m.hint}
                              </Text>
                            ) : null}
                          </Stack>
                        }
                        color="teal"
                        disabled={disabled}
                        styles={{
                          body: { alignItems: 'flex-start' },
                          labelWrapper: { paddingTop: 1 },
                        }}
                      />
                    </UnstyledButton>
                  )
                })}
              </Stack>
            </Radio.Group>
          </Stack>
        )
      })}
    </Stack>
  )
}
