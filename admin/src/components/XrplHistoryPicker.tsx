import { Radio, Stack, Text, UnstyledButton } from '@mantine/core'

export type XrplHistoryMode = 'stock' | 'day' | 'weeks' | 'full'

const MODES: {
  id: XrplHistoryMode
  title: string
  hint: string
}[] = [
  {
    id: 'stock',
    title: 'Stock · ~2 hours',
    hint: '2 000 ledgers — default xrpld window. Smallest disk, no archive RPC.',
  },
  {
    id: 'day',
    title: '1 day',
    hint: '25 000 ledgers. Typical public RPC day window.',
  },
  {
    id: 'weeks',
    title: '2 weeks',
    hint: '300 000 ledgers. Default for a new install. Disk ×2 of the window.',
  },
  {
    id: 'full',
    title: 'Full history',
    hint: 'Genesis → tip (ledger 32 570 on mainnet). No snapshot. Official archive ~39 TiB — not a VPS.',
  },
]

export function xrplHistoryInstallLabel(mode: XrplHistoryMode): string {
  switch (mode) {
    case 'stock':
      return 'Stock (~2h)'
    case 'day':
      return '1 day'
    case 'weeks':
      return '2 weeks'
    case 'full':
      return 'Full history'
  }
}

export function XrplHistoryPicker({
  value,
  onChange,
  disabled,
}: {
  value: XrplHistoryMode
  onChange: (mode: XrplHistoryMode) => void
  disabled?: boolean
}) {
  return (
    <Radio.Group value={value} onChange={(v) => onChange(v as XrplHistoryMode)}>
      <Stack gap={8}>
        {MODES.map((m) => {
          const selected = value === m.id
          return (
            <UnstyledButton
              key={m.id}
              disabled={disabled}
              onClick={() => onChange(m.id)}
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
                    <Text size="xs" c="dimmed" style={{ whiteSpace: 'normal' }}>
                      {m.hint}
                    </Text>
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
  )
}
