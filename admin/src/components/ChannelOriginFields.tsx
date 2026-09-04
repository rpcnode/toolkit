import { TextInput } from '@mantine/core'
import { blockProps } from '../lib/blockId'

export const ORIGIN_LOCAL = 'http://127.0.0.1:8094'
export const ORIGIN_CDN = 'http://127.0.0.1:8095'
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
      label="server"
      description="Published host:port other containers can reach (not 127.0.0.1 in Docker). Default :8094."
      placeholder={ORIGIN_LOCAL}
      value={origin}
      onChange={(e) => onChange(e.currentTarget.value.trim())}
      required
    />
  )
}
