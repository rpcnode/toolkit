import { ActionIcon, Select, Tooltip } from '@mantine/core'
import { IconExternalLink } from '@tabler/icons-react'
import type { InstanceInfo } from '../types'

type Props = {
  value: string
  instances: InstanceInfo[]
  agentEnv?: string
  onChange: (env: string, inst?: InstanceInfo) => void
}

const FALLBACK = ['mainnet', 'nile', 'shasta']

export function EnvSwitcher({ value, instances, agentEnv, onChange }: Props) {
  const envs = new Map<string, InstanceInfo>()
  for (const e of FALLBACK) {
    envs.set(e, { env: e, id: `tron-${e}`, current: e === agentEnv })
  }
  for (const inst of instances || []) {
    if (inst.env) envs.set(inst.env, inst)
  }

  const data = [...envs.values()].map((inst) => ({
    value: inst.env || '',
    label: inst.env || '',
  }))

  const selected = envs.get(value)
  const here = typeof window !== 'undefined' ? window.location.origin.replace(/\/$/, '') : ''
  const remote = (selected?.panel_base_url || selected?.status_url || selected?.public_base_url || '')
    .replace(/\/$/, '')
    .replace(/\/status\/?$/, '')
  const otherPanel =
    !!selected &&
    !selected.current &&
    selected.env !== agentEnv &&
    !!remote &&
    remote !== here

  return (
    <>
      <Select
        aria-label="Environment"
        size="xs"
        data={data}
        value={value}
        onChange={(v) => {
          if (!v) return
          onChange(v, envs.get(v))
        }}
        allowDeselect={false}
        w={110}
        comboboxProps={{ withinPortal: true }}
      />
      {otherPanel && selected?.status_url && (
        <Tooltip label="Open panel" withArrow>
          <ActionIcon
            component="a"
            href={selected.status_url}
            size="sm"
            variant="subtle"
            color="gray"
            aria-label="Open other panel"
          >
            <IconExternalLink size={14} />
          </ActionIcon>
        </Tooltip>
      )}
    </>
  )
}
