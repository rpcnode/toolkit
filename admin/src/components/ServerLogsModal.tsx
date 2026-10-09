import {
  ActionIcon,
  Box,
  Button,
  Center,
  Code,
  Group,
  Loader,
  Modal,
  SegmentedControl,
  Stack,
  Text,
  Tooltip,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconCheck, IconCopy, IconRefresh } from '@tabler/icons-react'
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { api, type AgentLogStream, type ServerLogsResponse } from '../api'
import { copyText } from '../lib/copyText'
import { blockProps } from '../lib/blockId'
import {
  pickDefaultLogStream,
  sortLogStreams,
  streamRelevantToNode,
} from '../lib/serverLogStreams'

type Props = {
  opened: boolean
  onClose: () => void
  serverId: string
  serverName?: string
  /** Prefer this stream id when the modal opens (e.g. install-tron). */
  defaultStream?: string
  /** When set, hide other networks' leaf streams (keep tip + this node). */
  network?: string
  env?: string
}

function scrollBoxToBottom(el: HTMLDivElement | null) {
  if (!el) return
  el.scrollTop = el.scrollHeight
}

export function ServerLogsModal({
  opened,
  onClose,
  serverId,
  serverName,
  defaultStream,
  network,
  env,
}: Props) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [payload, setPayload] = useState<ServerLogsResponse | null>(null)
  const [streamId, setStreamId] = useState<string>('')
  const [copied, setCopied] = useState(false)
  const scroller = useRef<HTMLDivElement | null>(null)
  const copiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const load = useCallback(async () => {
    if (!serverId) return
    setLoading(true)
    setError(null)
    try {
      const res = await api.serverLogs(serverId, { lines: 200 })
      const streams = sortLogStreams(
        (res.streams || []).filter((s) => streamRelevantToNode(s, network, env)),
        network,
      )
      setPayload({ ...res, streams })
      setStreamId((prev) => {
        if (prev && streams.some((s) => s.id === prev)) return prev
        return pickDefaultLogStream(streams, network, defaultStream)
      })
    } catch (e) {
      setPayload(null)
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [serverId, defaultStream, network, env])

  useEffect(() => {
    if (!opened) return
    void load()
  }, [opened, load])

  useEffect(
    () => () => {
      if (copiedTimer.current) clearTimeout(copiedTimer.current)
    },
    [],
  )

  const streams = payload?.streams || []
  const active: AgentLogStream | undefined = useMemo(
    () => streams.find((s) => s.id === streamId) || streams[0],
    [streams, streamId],
  )
  const lines = active?.lines || []
  const joined = lines.join('\n')

  useLayoutEffect(() => {
    if (loading || lines.length === 0) return
    scrollBoxToBottom(scroller.current)
  }, [joined, loading, lines.length])

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

  const title = `Host logs · ${serverName || serverId}`

  return (
    <Modal opened={opened} onClose={onClose} title={title} centered size="xl" {...blockProps('modal.server-logs')}>
      <Stack gap="sm">
        <Group justify="space-between" wrap="wrap">
          <Text size="xs" c="dimmed">
            Install · Snapshot · Errors · agents — each tab shows the file path on the host · v
            {payload?.version || '—'}
          </Text>
          <Group gap={6}>
            <Tooltip label={copied ? 'Copied' : 'Copy active stream'}>
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

        {loading && !payload ? (
          <Center mih={160}>
            <Loader size="sm" color="teal" />
          </Center>
        ) : error ? (
          <Text size="sm" c="red">
            {error}
          </Text>
        ) : streams.length === 0 ? (
          <Text size="sm" c="dimmed">
            No install/snapshot/error logs or agent units on this host yet.
          </Text>
        ) : (
          <>
            <SegmentedControl
              fullWidth
              size="xs"
              value={active?.id || streamId}
              onChange={setStreamId}
              data={streams.map((s) => ({
                value: s.id,
                label: s.label || s.id,
              }))}
              styles={{
                root: { flexWrap: 'wrap', height: 'auto' },
                label: { whiteSpace: 'normal', lineHeight: 1.2, paddingBlock: 6 },
              }}
            />
            {active?.path ? (
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
                  {active.path}
                </Code>
                <Text size="xs" c="dimmed">
                  {active.source || ''}
                </Text>
                <Tooltip label="Copy path">
                  <ActionIcon
                    size="sm"
                    variant="subtle"
                    color="gray"
                    aria-label="Copy path"
                    onClick={() => {
                      void copyText(active.path || '').then(() => {
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
