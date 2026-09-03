import { ActionIcon, Box, Card, Code, Group, Stack, Text, Title, Tooltip } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconCheck, IconCopy } from '@tabler/icons-react'
import { useLayoutEffect, useRef, useState } from 'react'
import type { StatusPayload } from '../types'
import { copyText } from '../lib/copyText'
import { blockProps } from '../lib/blockId'
import { resolveEnv, resolveNetwork } from '../lib/network'

export function agentErrorLines(status: StatusPayload | null | undefined): string[] {
  const out: string[] = []
  const seen = new Set<string>()
  const push = (s: unknown) => {
    const t = String(s || '').trim()
    if (!t || seen.has(t)) return
    seen.add(t)
    out.push(t)
  }
  if (Array.isArray(status?.errors?.lines)) {
    for (const line of status.errors.lines) push(line)
  }
  push(status?.errors?.last)
  push(status?.start_error)
  push(status?.agent?.last_error)
  push(status?.snapshot?.error)
  const phase = String(status?.lifecycle?.phase || status?.ui_phase || '').toLowerCase()
  const ns = String(status?.node_status || status?.lifecycle?.node_status || '').toLowerCase()
  if (phase === 'error' || ns.includes('error')) {
    push(status?.lifecycle?.detail)
  }
  return out
}

export function AgentErrorsPanel({ status }: { status: StatusPayload }) {
  const lines = agentErrorLines(status)
  if (lines.length === 0) return null

  const scroller = useRef<HTMLDivElement | null>(null)
  const [copied, setCopied] = useState(false)
  const net = status.errors?.network || resolveNetwork(status)
  const env = status.errors?.env || resolveEnv(status)
  const path =
    status.errors?.path ||
    (net && env ? `/var/log/rpcnode/errors/${net}-${env}.log` : '')
  const scope = net && env ? ` · ${net}/${env}` : ''
  const joined = lines.join('\n')

  useLayoutEffect(() => {
    const el = scroller.current
    if (!el) return
    el.scrollTop = el.scrollHeight
  }, [joined])

  return (
    <Card withBorder {...blockProps('node.detail.errors-panel')}>
      <Group justify="space-between" mb="sm" wrap="wrap">
        <Group gap="xs">
          <Title order={4} c="red" tt="uppercase" size="xs">
            Agent errors{scope}
          </Title>
          <Tooltip label={copied ? 'Copied' : 'Copy errors'}>
            <ActionIcon
              size="sm"
              variant="light"
              color={copied ? 'teal' : 'red'}
              aria-label="Copy errors"
              onClick={() => {
                void copyText(joined)
                  .then(() => {
                    setCopied(true)
                    notifications.show({ color: 'teal', message: 'Errors copied', autoClose: 2000 })
                    window.setTimeout(() => setCopied(false), 1500)
                  })
                  .catch(() => {
                    notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
                  })
              }}
            >
              {copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
            </ActionIcon>
          </Tooltip>
        </Group>
        <Text size="xs" c="dimmed">
          {lines.length} classified
        </Text>
      </Group>
      <Stack gap={4} mb="sm">
        <Text size="xs" c="dimmed" tt="uppercase" fw={700}>
          Path on host
        </Text>
        <Code className="mono" style={{ wordBreak: 'break-all', fontSize: 12 }}>
          {path}
        </Code>
      </Stack>
      <Box ref={scroller} style={{ maxHeight: 240, overflow: 'auto' }}>
        <Code block className="mono" style={{ whiteSpace: 'pre-wrap', fontSize: 12, color: 'var(--mantine-color-red-7)' }}>
          {joined}
        </Code>
      </Box>
    </Card>
  )
}
