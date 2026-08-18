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

export function fallbackInstallGroups(network?: string, env?: string): InstallOptionGroup[] {
  const net = (network || '').toLowerCase()
  const e = (env || '').toLowerCase()
  if (net === 'tron' && e === 'mainnet') {
    return [TRON_MAINNET_SNAPSHOT_OPTIONS]
  }
  if (net === 'xrpl' && (e === 'mainnet' || e === 'testnet')) {
    return [XRPL_HISTORY_OPTIONS]
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
