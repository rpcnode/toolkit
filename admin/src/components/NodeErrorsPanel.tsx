import { ActionIcon, Box, Card, Code, Group, Loader, Stack, Text, Title, Tooltip } from '@mantine/core'
import { IconCheck, IconCopy, IconRefresh } from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { getJSONResult, type ApiCallResult } from '../api'
import { copyText } from '../lib/copyText'
import { blockProps } from '../lib/blockId'
import { ApiFetchIssue } from './ApiFetchIssue'

type Props = {
  nodeId: string
  network: string
  env: string
  liveTestError?: string
  /** Poll host process log while the chain unit is up or after a failed start. */
  autoFetch?: boolean
}

type LogsPayload = {
  ok?: boolean
  node_id?: string
  path?: string
  lines?: string[]
  truncated?: boolean
  error?: string
  message?: string
}

const ERROR_LINE =
  /\b(error|fatal|panic|failed|exception|rejected|warning)\b|Config setting for|only applied on|^Error:/i

function extractErrorLines(lines: string[]): string[] {
  const out: string[] = []
  const seen = new Set<string>()
  for (const line of lines) {
    const t = line.trim()
    if (!t || !ERROR_LINE.test(t)) continue
    if (seen.has(t)) continue
    seen.add(t)
    out.push(t)
  }
  return out.slice(-24)
}

function logsPath(result: ApiCallResult<LogsPayload>): string {
  return result.data.path?.trim() || ''
}

/** Classified errors for one node — host process log tail (Kotlin panel has no status.json). */
export function NodeErrorsPanel({ nodeId, network, env, liveTestError, autoFetch = false }: Props) {
  const [lines, setLines] = useState<string[]>([])
  const [rawTail, setRawTail] = useState<string[]>([])
  const [rawCount, setRawCount] = useState(0)
  const [fetchResult, setFetchResult] = useState<ApiCallResult<LogsPayload> | null>(null)
  const [loading, setLoading] = useState(false)
  const [copied, setCopied] = useState(false)
  const scroller = useRef<HTMLDivElement | null>(null)

  const load = useCallback(async () => {
    if (!nodeId) return
    setLoading(true)
    const q = new URLSearchParams({ lines: '200' })
    const path = `/api/nodes/${encodeURIComponent(nodeId)}/logs?${q}`
    const result = await getJSONResult<LogsPayload>(path)
    setFetchResult(result)
    if (!result.ok) {
      setLines([])
      setRawTail([])
      setRawCount(0)
      setLoading(false)
      return
    }
    const rawLines = Array.isArray(result.data.lines) ? result.data.lines : []
    setRawCount(rawLines.length)
    setRawTail(rawLines.slice(-8))
    setLines(extractErrorLines(rawLines))
    setLoading(false)
  }, [nodeId])

  useEffect(() => {
    if (!autoFetch) return
    void load()
    const t = window.setInterval(() => void load(), 15_000)
    return () => window.clearInterval(t)
  }, [autoFetch, load])

  const extra = (liveTestError || '').trim()
  const allLines = [...lines, ...(extra && !lines.includes(extra) ? [extra] : [])]
  const joined = allLines.join('\n')
  const scope = network && env ? ` · ${network}/${env}` : ''
  const logPath = fetchResult ? logsPath(fetchResult) : ''

  useLayoutEffect(() => {
    const el = scroller.current
    if (!el) return
    el.scrollTop = el.scrollHeight
  }, [joined])

  if (!autoFetch && !fetchResult && allLines.length === 0) {
    return (
      <Group justify="flex-end">
        <Tooltip label="Load errors from node log">
          <ActionIcon
            size="xs"
            variant="subtle"
            color="gray"
            aria-label="Load node errors"
            loading={loading}
            onClick={() => void load()}
          >
            <IconRefresh size={12} />
          </ActionIcon>
        </Tooltip>
      </Group>
    )
  }

  if (loading && !fetchResult) {
    return (
      <Group gap={6}>
        <Loader size={12} />
        <Text size="xs" c="dimmed">
          GET /api/nodes/…/logs…
        </Text>
      </Group>
    )
  }

  if (fetchResult && !fetchResult.ok) {
    return (
      <Stack gap="sm">
        <ApiFetchIssue title="Node log request failed" result={fetchResult} detail={logPath || undefined} />
        <Group justify="flex-end">
          <Tooltip label="Retry">
            <ActionIcon size="xs" variant="subtle" color="gray" aria-label="Retry" loading={loading} onClick={() => void load()}>
              <IconRefresh size={12} />
            </ActionIcon>
          </Tooltip>
        </Group>
      </Stack>
    )
  }

  if (allLines.length === 0) {
    return (
      <Stack gap="sm">
        {fetchResult?.ok ? (
          <Stack gap={4}>
            <Code className="mono" style={{ fontSize: 11 }}>
              {fetchResult.request} → {fetchResult.status}
            </Code>
            {logPath ? (
              <Text size="xs" c="dimmed" className="mono" style={{ wordBreak: 'break-all' }}>
                {logPath}
              </Text>
            ) : null}
            <Text size="xs" c="dimmed">
              {rawCount === 0
                ? 'Log file is empty — no stderr captured yet (systemd → logs/node.out)'
                : `${rawCount} log lines read — no error-like lines matched`}
            </Text>
          </Stack>
        ) : null}
        {rawTail.length > 0 ? (
          <Box style={{ maxHeight: 120, overflow: 'auto' }}>
            <Text size="xs" c="dimmed" mb={4}>
              Recent log tail
            </Text>
            <Code block className="mono" style={{ whiteSpace: 'pre-wrap', fontSize: 11 }}>
              {rawTail.join('\n')}
            </Code>
          </Box>
        ) : null}
        <Group justify="flex-end">
          <Tooltip label="Reload node log">
            <ActionIcon size="xs" variant="subtle" color="gray" aria-label="Reload" loading={loading} onClick={() => void load()}>
              <IconRefresh size={12} />
            </ActionIcon>
          </Tooltip>
        </Group>
      </Stack>
    )
  }

  return (
    <Card withBorder {...blockProps('node.detail.errors-panel')}>
      <Group justify="space-between" mb="sm" wrap="wrap">
        <Group gap="xs">
          <Title order={4} c="red" tt="uppercase" size="xs">
            Node errors{scope}
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
          <Tooltip label="Reload node log">
            <ActionIcon size="sm" variant="light" color="gray" aria-label="Reload" loading={loading} onClick={() => void load()}>
              <IconRefresh size={14} />
            </ActionIcon>
          </Tooltip>
        </Group>
        <Text size="xs" c="dimmed">
          {allLines.length} matched · {rawCount} lines
        </Text>
      </Group>
      {fetchResult ? (
        <Code className="mono" style={{ fontSize: 11, wordBreak: 'break-all', display: 'block', marginBottom: 8 }}>
          {fetchResult.request} → {fetchResult.status}
        </Code>
      ) : null}
      {logPath ? (
        <Stack gap={4} mb="sm">
          <Text size="xs" c="dimmed" tt="uppercase" fw={700}>
            Log on host
          </Text>
          <Code className="mono" style={{ wordBreak: 'break-all', fontSize: 12 }}>
            {logPath}
          </Code>
        </Stack>
      ) : null}
      <Box ref={scroller} style={{ maxHeight: 240, overflow: 'auto' }}>
        <Code block className="mono" style={{ whiteSpace: 'pre-wrap', fontSize: 12, color: 'var(--mantine-color-red-7)' }}>
          {joined}
        </Code>
      </Box>
    </Card>
  )
}
