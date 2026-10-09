import { Box, Card, Code, Group, Stack, Text, Title } from '@mantine/core'
import { useLayoutEffect, useRef } from 'react'
import type { StatusPayload } from '../types'
import { blockProps } from '../lib/blockId'

export function AgentInstallLogPanel({ status }: { status: StatusPayload }) {
  const info = status.install_log
  const path =
    info?.path ||
    (typeof status.lifecycle?.install_log === 'string' ? status.lifecycle.install_log : '')
  const lines = Array.isArray(info?.lines) ? info.lines.filter(Boolean) : []
  if (!path && lines.length === 0) return null

  const scroller = useRef<HTMLDivElement | null>(null)
  const joined = lines.join('\n')
  const net = info?.network || ''
  const scope = net ? ` · ${net}` : ''

  useLayoutEffect(() => {
    const el = scroller.current
    if (!el) return
    el.scrollTop = el.scrollHeight
  }, [joined])

  return (
    <Card withBorder {...blockProps('node.detail.install-log-panel')}>
      <Group justify="space-between" mb="sm" wrap="wrap">
        <Title order={4} tt="uppercase" size="xs">
          Install log{scope}
        </Title>
        <Text size="xs" c="dimmed">
          {lines.length ? `${lines.length} lines` : 'waiting for first step'}
        </Text>
      </Group>
      <Stack gap={4} mb="sm">
        <Text size="xs" c="dimmed" tt="uppercase" fw={700}>
          Path on host
        </Text>
        <Code className="mono" style={{ wordBreak: 'break-all', fontSize: 12 }}>
          {path || '—'}
        </Code>
      </Stack>
      <Box ref={scroller} style={{ maxHeight: 240, overflow: 'auto' }}>
        <Code block className="mono" style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
          {joined || 'No install steps written yet.'}
        </Code>
      </Box>
    </Card>
  )
}
