import { useCallback, useEffect, useState } from 'react'
import { ActionIcon, Alert, Code, Group, Stack, Text, Tooltip } from '@mantine/core'
import { IconAlertTriangle, IconInfoCircle, IconRefresh } from '@tabler/icons-react'
import { api, type NodeDiagnosticItem } from '../api'
import { blockProps } from '../lib/blockId'

const COLOR: Record<string, string> = { error: 'red', warn: 'yellow', info: 'blue', unknown: 'gray' }

/**
 * Node page warnings from the checks declared in chains/<id>/network.yml → diagnostics.
 * Shows only what needs attention (ok checks stay hidden); nothing is rendered for a network without checks.
 */
export function NodeDiagnosticsPanel({ nodeId }: { nodeId: string }) {
  const [items, setItems] = useState<NodeDiagnosticItem[]>([])
  const [loading, setLoading] = useState(false)
  const [agentNote, setAgentNote] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!nodeId) return
    setLoading(true)
    try {
      const res = await api.nodeDiagnostics(nodeId)
      setItems(res.items || [])
      setAgentNote(res.error === 'agent_too_old' ? res.message || 'Update the host agent' : null)
    } catch {
      /* a failed probe must not clutter the page — the next poll retries */
    } finally {
      setLoading(false)
    }
  }, [nodeId])

  useEffect(() => {
    void load()
    // checks read logs / ask the public IP: every 5 min is enough
    const t = window.setInterval(() => void load(), 5 * 60_000)
    return () => window.clearInterval(t)
  }, [load])

  const shown = items.filter((i) => i.level !== 'ok')
  if (shown.length === 0 && !agentNote) return null

  return (
    <Stack gap="xs" {...blockProps('node.detail.diagnostics')}>
      <Group justify="space-between">
        <Text size="xs" fw={600} c="dimmed" tt="uppercase">
          Node checks
        </Text>
        <Tooltip label="Run the checks again">
          <ActionIcon size="sm" variant="subtle" color="gray" loading={loading} onClick={() => void load()}>
            <IconRefresh size={14} />
          </ActionIcon>
        </Tooltip>
      </Group>
      {agentNote ? (
        <Alert color="gray" variant="light" p="xs">
          <Text size="sm">{agentNote}</Text>
        </Alert>
      ) : null}
      {shown.map((i) => (
        <Alert
          key={i.id}
          color={COLOR[i.level] || 'gray'}
          variant="light"
          p="xs"
          icon={i.level === 'info' ? <IconInfoCircle size={16} /> : <IconAlertTriangle size={16} />}
          title={i.title}
        >
          {i.detail ? <Text size="sm">{i.detail}</Text> : null}
          {i.hint ? (
            <Code block mt={6} style={{ whiteSpace: 'pre-wrap' }}>
              {i.hint}
            </Code>
          ) : null}
        </Alert>
      ))}
    </Stack>
  )
}
