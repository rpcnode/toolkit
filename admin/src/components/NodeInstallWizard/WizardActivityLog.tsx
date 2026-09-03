import { ActionIcon, Box, Code, Group, Stack, Tooltip } from '@mantine/core'
import { IconCheck, IconCopy } from '@tabler/icons-react'
import type { RefObject } from 'react'

export type WizardActivityLogProps = {
  lines: string[]
  joined: string
  copied: boolean
  onCopy: () => void
  scrollerRef: RefObject<HTMLDivElement | null>
}

export function WizardActivityLog({
  lines,
  joined,
  copied,
  onCopy,
  scrollerRef,
}: WizardActivityLogProps) {
  if (lines.length === 0) return null
  return (
    <Stack gap={6}>
      <Group justify="flex-end" gap={4}>
        <Tooltip label={copied ? 'Copied' : 'Copy logs'}>
          <ActionIcon
            size="sm"
            variant="light"
            color={copied ? 'teal' : 'gray'}
            aria-label="Copy logs"
            onClick={onCopy}
          >
            {copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
          </ActionIcon>
        </Tooltip>
      </Group>
      <Box ref={scrollerRef} style={{ maxHeight: 280, overflow: 'auto' }}>
        <Code block className="mono" style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
          {joined}
        </Code>
      </Box>
    </Stack>
  )
}
