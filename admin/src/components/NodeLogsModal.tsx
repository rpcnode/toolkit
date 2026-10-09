import {
  ActionIcon,
  Box,
  Button,
  Center,
  Code,
  Group,
  Loader,
  Modal,
  Stack,
  Text,
  Tooltip,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconCheck, IconCopy, IconRefresh } from '@tabler/icons-react'
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { api } from '../api'
import { blockProps } from '../lib/blockId'
import { copyText } from '../lib/copyText'

type Props = {
  opened: boolean
  onClose: () => void
  nodeId: string
  title?: string
  /** Auto-refresh while open (ms). 0 = manual only. */
  pollMs?: number
  lines?: number
}

function scrollBoxToBottom(el: HTMLDivElement | null) {
  if (!el) return
  el.scrollTop = el.scrollHeight
}

/** Tail of the node process log from the host agent (`GET /api/nodes/{id}/logs`). */
export function NodeLogsModal({
  opened,
  onClose,
  nodeId,
  title,
  pollMs = 5_000,
  lines = 200,
}: Props) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [path, setPath] = useState('')
  const [logLines, setLogLines] = useState<string[]>([])
  const [truncated, setTruncated] = useState(false)
  const [copied, setCopied] = useState(false)
  const scroller = useRef<HTMLDivElement | null>(null)
  const copiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const load = useCallback(async () => {
    if (!nodeId) return
    setLoading(true)
    setError(null)
    try {
      const res = await api.workloadsNodeLogs(nodeId, { lines })
      if (res.ok === false) {
        setLogLines([])
        setPath('')
        setError(res.message || res.error || 'logs unavailable')
        return
      }
      setLogLines(Array.isArray(res.lines) ? res.lines : [])
      setPath(res.path || '')
      setTruncated(!!res.truncated)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [nodeId, lines])

  useEffect(() => {
    if (!opened) return
    void load()
  }, [opened, load])

  useEffect(() => {
    if (!opened || !pollMs) return
    const t = window.setInterval(() => void load(), pollMs)
    return () => window.clearInterval(t)
  }, [opened, pollMs, load])

  useEffect(
    () => () => {
      if (copiedTimer.current) clearTimeout(copiedTimer.current)
    },
    [],
  )

  const joined = logLines.join('\n')

  useLayoutEffect(() => {
    if (loading || logLines.length === 0) return
    scrollBoxToBottom(scroller.current)
  }, [joined, loading, logLines.length])

  function copyLogs() {
    if (!joined) return
    void copyText(joined)
      .then(() => {
        setCopied(true)
        if (copiedTimer.current) clearTimeout(copiedTimer.current)
        copiedTimer.current = setTimeout(() => setCopied(false), 1500)
        notifications.show({ color: 'teal', message: 'Logs copied', autoClose: 1500 })
      })
      .catch(() => {
        notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
      })
  }

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={title ? `Node logs · ${title}` : 'Node logs'}
      centered
      size="xl"
      {...blockProps('modal.node-logs')}
    >
      <Stack gap="sm">
        <Group justify="space-between" wrap="wrap">
          <Text size="xs" c="dimmed">
            Host process log from the agent
            {truncated ? ' · truncated' : ''}
          </Text>
          <Group gap={6}>
            <Tooltip label={copied ? 'Copied' : 'Copy'}>
              <ActionIcon
                size="sm"
                variant="light"
                color={copied ? 'teal' : 'gray'}
                disabled={!joined}
                aria-label="Copy logs"
                onClick={copyLogs}
              >
                {copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
              </ActionIcon>
            </Tooltip>
            <Button
              size="xs"
              variant="light"
              leftSection={<IconRefresh size={12} />}
              loading={loading}
              onClick={() => void load()}
            >
              Refresh
            </Button>
          </Group>
        </Group>

        {loading && logLines.length === 0 && !error ? (
          <Center mih={160}>
            <Loader size="sm" color="teal" />
          </Center>
        ) : error && logLines.length === 0 ? (
          <Text size="sm" c="red">
            {error}
          </Text>
        ) : (
          <>
            {path ? (
              <Group gap={6} wrap="nowrap" align="flex-start">
                <Code
                  className="mono"
                  style={{
                    flex: 1,
                    minWidth: 0,
                    wordBreak: 'break-all',
                    whiteSpace: 'pre-wrap',
                    fontSize: 12,
                  }}
                >
                  {path}
                </Code>
                <Tooltip label="Copy path">
                  <ActionIcon
                    size="sm"
                    variant="subtle"
                    color="gray"
                    aria-label="Copy path"
                    onClick={() => {
                      void copyText(path).then(() => {
                        notifications.show({
                          color: 'teal',
                          message: 'Path copied',
                          autoClose: 1500,
                        })
                      })
                    }}
                  >
                    <IconCopy size={14} />
                  </ActionIcon>
                </Tooltip>
              </Group>
            ) : null}
            <Box ref={scroller} style={{ maxHeight: 420, overflow: 'auto' }}>
              <Code block className="mono" style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
                {joined || '(empty)'}
              </Code>
            </Box>
          </>
        )}
      </Stack>
    </Modal>
  )
}
