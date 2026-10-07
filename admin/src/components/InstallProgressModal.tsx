import {
  ActionIcon,
  Alert,
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
import { IconAlertTriangle, IconCheck, IconCopy, IconRefresh } from '@tabler/icons-react'
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { api, type AgentLogStream, type ServerLogsResponse } from '../api'
import { copyText } from '../lib/copyText'
import {
  pickDefaultLogStream,
  sortLogStreams,
  streamRelevantToNode,
} from '../lib/serverLogStreams'
import { blockProps } from '../lib/blockId'

export type InstallProgressOutcome = 'running' | 'ok' | 'fail'

type Props = {
  opened: boolean
  onClose: () => void
  serverId?: string
  serverName?: string
  network?: string
  env?: string
  outcome: InstallProgressOutcome
  error?: string | null
  /** Local wizard ACK lines (ports / provision / start). */
  wizardLines?: string[]
  onRefreshStatus?: () => void
}

function scrollBoxToBottom(el: HTMLDivElement | null) {
  if (!el) return
  el.scrollTop = el.scrollHeight
  requestAnimationFrame(() => {
    el.scrollTop = el.scrollHeight
  })
}

const POLL_MS = 10_000

export function InstallProgressModal({
  opened,
  onClose,
  serverId,
  serverName,
  network,
  env,
  outcome,
  error,
  wizardLines = [],
  onRefreshStatus,
}: Props) {
  const [loading, setLoading] = useState(false)
  const [fetchError, setFetchError] = useState<string | null>(null)
  const [payload, setPayload] = useState<ServerLogsResponse | null>(null)
  const [streamId, setStreamId] = useState('')
  const [copied, setCopied] = useState(false)
  const [nextIn, setNextIn] = useState(10)
  const [fetchedAt, setFetchedAt] = useState<Date | null>(null)
  const scroller = useRef<HTMLDivElement | null>(null)
  const copiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const refreshStatus = useRef(onRefreshStatus)
  refreshStatus.current = onRefreshStatus

  const load = useCallback(async () => {
    if (!serverId) return
    setLoading(true)
    setFetchError(null)
    try {
      const res = await api.serverLogs(serverId, { lines: 200 })
      const streams = sortLogStreams(
        (res.streams || []).filter((s) => streamRelevantToNode(s, network, env)),
        network,
      )
      setPayload({ ...res, streams })
      setFetchedAt(new Date())
      setStreamId((prev) => {
        if (prev && streams.some((s) => s.id === prev)) return prev
        return pickDefaultLogStream(streams, network)
      })
    } catch (e) {
      setFetchError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
      setNextIn(10)
    }
  }, [serverId, network, env])

  useEffect(() => {
    if (!opened) return
    void load()
    void refreshStatus.current?.()
    if (outcome !== 'running') return
    const pull = window.setInterval(() => {
      void load()
      void refreshStatus.current?.()
    }, POLL_MS)
    const tick = window.setInterval(() => {
      setNextIn((n) => (n <= 1 ? 10 : n - 1))
    }, 1000)
    return () => {
      window.clearInterval(pull)
      window.clearInterval(tick)
    }
  }, [opened, outcome, load])

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
  const hostLines = active?.lines || []
  const wizardBlock = wizardLines.length > 0 ? wizardLines.join('\n') : ''
  const hostBlock = hostLines.join('\n')
  const joined = [wizardBlock && `— panel —\n${wizardBlock}`, hostBlock && `— ${active?.label || 'host'} —\n${hostBlock}`]
    .filter(Boolean)
    .join('\n\n')

  useLayoutEffect(() => {
    if (hostLines.length === 0 && wizardLines.length === 0) return
    scrollBoxToBottom(scroller.current)
  }, [joined, hostLines.length, wizardLines.length])

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

  const netLabel = [network, env].filter(Boolean).join('/')
  const title =
    outcome === 'ok'
      ? 'Install succeeded'
      : outcome === 'fail'
        ? 'Install failed'
        : `Installing${netLabel ? ` ${netLabel}` : ''}`

  return (
    <Modal
      {...blockProps('modal.install-progress')}
      opened={opened}
      onClose={onClose}
      title={title}
      centered
      size="xl"
      // A failure must survive a stray click next to the dialog — the operator
      // dismisses it deliberately, after reading it.
      closeOnClickOutside={false}
      closeOnEscape={outcome !== 'running'}
    >
      <Stack gap="sm">
        {outcome === 'ok' ? (
          <Alert color="teal" variant="light" icon={<IconCheck size={16} />}>
            Install completed successfully.
          </Alert>
        ) : outcome === 'fail' ? (
          <Alert
            color="red"
            variant="light"
            icon={<IconAlertTriangle size={16} />}
            title={`Install did not complete${netLabel ? ` · ${netLabel}` : ''}`}
          >
            <Stack gap={6}>
              {error ? (
                <Code
                  block
                  className="mono"
                  style={{ whiteSpace: 'pre-wrap', fontSize: 12, background: 'transparent' }}
                >
                  {error}
                </Code>
              ) : (
                <Text size="sm">No error text from the agent — read the log below.</Text>
              )}
              <Group gap={6}>
                <Button
                  size="xs"
                  variant="light"
                  color="red"
                  leftSection={<IconCopy size={14} />}
                  disabled={!error}
                  onClick={() => {
                    void copyText(String(error || ''))
                      .then(() =>
                        notifications.show({
                          color: 'teal',
                          message: 'Error copied',
                          autoClose: 1500,
                        }),
                      )
                      .catch(() =>
                        notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 }),
                      )
                  }}
                >
                  Copy error
                </Button>
              </Group>
            </Stack>
          </Alert>
        ) : (
          <Alert color="yellow" variant="light">
            <Group gap="xs" wrap="nowrap">
              <Loader size="xs" color="yellow" />
              <Text size="sm">
                Install in progress
                {netLabel ? ` · ${netLabel}` : ''}
                {serverName ? ` · ${serverName}` : ''}. Logs refresh every 10s
                {nextIn > 0 ? ` (next ${nextIn}s)` : ''}.
              </Text>
            </Group>
          </Alert>
        )}

        <Group justify="space-between" wrap="wrap">
          <Text size="xs" c="dimmed">
            {active?.path ||
              (network ? `/var/log/rpcnode/${network}-install.log` : 'install log on host')}
            {fetchedAt ? ` · ${fetchedAt.toLocaleTimeString()}` : ''}
          </Text>
          <Group gap={6}>
            <Tooltip label={copied ? 'Copied' : 'Copy logs'}>
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
        ) : fetchError && !payload ? (
          <Text size="sm" c="red">
            {fetchError}
          </Text>
        ) : streams.length > 1 ? (
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
        ) : null}

        <Box ref={scroller} style={{ maxHeight: 420, overflow: 'auto' }}>
          {joined ? (
            <Code block className="mono" style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
              {joined}
            </Code>
          ) : (
            <Text size="sm" c="dimmed" className="mono">
              Waiting for host log lines…
            </Text>
          )}
        </Box>

        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {outcome === 'running' ? 'Hide' : 'Close'}
          </Button>
        </Group>
      </Stack>
    </Modal>
  )
}
