import { Radio, Stack, Text, UnstyledButton } from '@mantine/core'

export type RemoveNodeMode = 'wipe' | 'agents' | 'panel'

const MODES: {
  id: RemoveNodeMode
  title: string
  hint: string
}[] = [
  {
    id: 'wipe',
    title: 'Full removal from the server',
    hint: 'Stop the node, remove leaf agents and units, wipe chain data, conf and logs.',
  },
  {
    id: 'agents',
    title: 'Remove agent from the server',
    hint: 'Stop the node and leaf agents, drop systemd units. Keep /data chain files.',
  },
  {
    id: 'panel',
    title: 'Remove from the panel',
    hint: 'Drop this row only. The node keeps running on the host.',
  },
]

export function removeModeToRequest(mode: RemoveNodeMode): {
  mode: RemoveNodeMode
  delete_files: boolean
} {
  return {
    mode,
    delete_files: mode === 'wipe',
  }
}

export function removeSubmitLabel(mode: RemoveNodeMode, retry: boolean): string {
  if (mode === 'panel') return 'Remove from panel'
  if (mode === 'agents') return retry ? 'Retry remove agent' : 'Remove agent'
  return retry ? 'Retry remove + wipe' : 'Remove + wipe'
}

export function RemoveNodeModePicker({
  value,
  onChange,
  disabled,
}: {
  value: RemoveNodeMode
  onChange: (mode: RemoveNodeMode) => void
  disabled?: boolean
}) {
  return (
    <Radio.Group value={value} onChange={(v) => onChange(v as RemoveNodeMode)}>
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
                border: `1px solid ${selected ? 'var(--mantine-color-red-6)' : 'var(--mantine-color-dark-4)'}`,
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
                color="red"
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
