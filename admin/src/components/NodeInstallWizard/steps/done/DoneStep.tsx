import { Group, Loader, Text } from '@mantine/core'
import { blockProps } from '../../../../lib/blockId'

export function DoneStep() {
  return (
    <Group gap="sm" {...blockProps('node.detail.wizard.step.done')}>
      <Loader size="sm" color="teal" />
      <Text c="dimmed" size="sm">
        Opening ops…
      </Text>
    </Group>
  )
}
