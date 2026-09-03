import { TextInput } from '@mantine/core'
import { blockProps } from '../lib/blockId'

export const ORIGIN_LOCAL = 'http://127.0.0.1:8093'
export const ORIGIN_PROD = 'https://toolkit.rpcnode.dev'

export type OriginPresets = { panel?: string; local?: string; prod?: string }

export function ChannelOriginFields({
  origin,
  onChange,
}: {
  origin: string
  onChange: (next: string) => void
  presets?: OriginPresets
  compact?: boolean
}) {
  return (
    <TextInput
      {...blockProps('shared.channel-origin')}
      label="Install origin"
      description="Where agents fetch clients and the agent jar. Default is this panel."
      placeholder={ORIGIN_LOCAL}
      value={origin}
      onChange={(e) => onChange(e.currentTarget.value.trim())}
      required
    />
  )
}
