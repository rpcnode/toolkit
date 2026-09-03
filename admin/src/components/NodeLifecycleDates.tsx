import { Text } from '@mantine/core'
import { formatNodeWhen } from '../lib/format'

type Props = {
  added?: string | null
  install?: string | null
  synced?: string | null
  updated?: string | null
  inline?: boolean
}

export function NodeLifecycleDates({ added, install, synced, updated, inline }: Props) {
  const parts = [
    ['Added', added],
    ['Install', install],
    ['Synced', synced],
    ['Updated', updated],
  ] as const
  if (inline) {
    return (
      <Text span size="xs" c="dimmed" title="Added · install started · first synced · last update">
        {parts.map(([label, value], i) => (
          <span key={label}>
            {i > 0 ? ' · ' : null}
            {label} {formatNodeWhen(value)}
          </span>
        ))}
      </Text>
    )
  }
  return (
    <div className="node-dates" title="Added · install started · first synced · last update">
      {parts.map(([label, value]) => (
        <div key={label} className="node-dates__row">
          <span className="node-dates__label">{label}</span>
          <span className="node-dates__value" title={formatNodeWhen(value)}>
            {formatNodeWhen(value, true)}
          </span>
        </div>
      ))}
    </div>
  )
}
