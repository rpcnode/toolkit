import { Badge } from '@mantine/core'
import { healthColor } from '../lib/format'

type Props = {
  value?: string | null
  label?: string
  color?: string
}

export function StatusBadge({ value, label, color }: Props) {
  const v = label || value || 'n/a'
  return (
    <Badge color={color || healthColor(value || label)} variant="light" tt="none">
      {v}
    </Badge>
  )
}
