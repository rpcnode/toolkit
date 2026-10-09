import { Alert, Button, Code, Group, Modal, Progress, ScrollArea, Stack, Text, ThemeIcon } from '@mantine/core'
import { useEffect, useRef } from 'react'
import {
  IconAlertTriangle,
  IconCheck,
  IconCircleDot,
  IconHistory,
} from '@tabler/icons-react'
import type { ClientUpdateInfo } from '../api'
import { formatClientVersion } from '../lib/format'
import { blockProps } from '../lib/blockId'

const STEPS = [
  { id: 'stopped', label: 'Stopped' },
  { id: 'updating', label: 'Updating' },
  { id: 'started', label: 'Started' },
] as const

function hideURL(s: string): string {
  return String(s || '')
    .replace(/https?:\/\/\S+/gi, '')
    .replace(/\s{2,}/g, ' ')
    .trim()
}

function stepIndex(step?: string | null, events?: Array<{ id?: string }> | null): number {
  const fromEvents = (events || [])
    .map((e) => (e.id || '').toLowerCase())
    .filter(Boolean)
  const order = STEPS.map((s) => s.id)
  let best = -1
  for (const id of fromEvents) {
    const i = order.indexOf(id as (typeof STEPS)[number]['id'])
    if (i > best) best = i
  }
  const s = (step || '').toLowerCase()
  if (s === 'done' || s === 'started') return STEPS.length - 1
  if (s === 'error') return best >= 0 ? best : 0
  const i = order.indexOf(s as (typeof STEPS)[number]['id'])
  if (i >= 0) return Math.max(i, best)
  if (s === 'download' || s === 'install' || s === 'check') return Math.max(1, best)
  return Math.max(0, best)
}

type Props = {
  opened: boolean
  onClose: () => void
  network: string
  env: string
  current?: string
  latest?: string
  updateAvailable?: boolean
  info?: ClientUpdateInfo | null
  /** The update request itself was rejected (panel or host agent) — shown as a failure with the full text. */
  startError?: string
  started: boolean
  requestBusy: boolean
  rollbackBusy?: boolean
  onStart: () => void
  onRollback?: () => void
}

export function ClientUpdateModal({
  opened,
  onClose,
  network,
  env,
  current,
  latest,
  updateAvailable,
  info,
  startError,
  started,
  requestBusy,
  rollbackBusy,
  onStart,
  onRollback,
}: Props) {
  const phase = (info?.phase || '').toLowerCase()
  const step = (info?.step || '').toLowerCase()
  const running = started || phase === 'updating'
  const failed = (started && phase === 'error') || !!startError
  const done =
    !failed &&
    started &&
    (step === 'started' || step === 'done' || (phase === 'idle' && Number(info?.pct) >= 99))
  const showProgress = running || done || failed
  const pct = Math.max(0, Math.min(100, Number(info?.pct) || 0))
  const idx = failed ? -1 : done ? STEPS.length - 1 : Math.max(0, stepIndex(step, info?.events))
  const startErr = String(startError || '').trim()
  // The alert shows the first line only; the full text (with the stack trace) is in the logs below.
  const detail = hideURL(startErr.split('\n')[0] || info?.detail || '')
  const err = hideURL(info?.last_error || '')
  // Panel/agent error text goes first, then the host log tail — everything needed to debug is in this window.
  const logTail = [startErr ? `# update request failed\n${startErr}` : '', String(info?.log_tail || '').trim()]
    .filter(Boolean)
    .join('\n\n')
  const logViewport = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = logViewport.current
    if (el) el.scrollTop = el.scrollHeight
  }, [logTail])
  const previousVersion =
    formatClientVersion(info?.previous_version || current || '') || info?.previous_version || current || ''
  const curLabel = formatClientVersion(current || info?.local || '') || '—'
  const latestLabel = formatClientVersion(latest || info?.latest || '')
  const title = failed
    ? 'Client update failed'
    : done
      ? 'Client updated'
      : running
        ? 'Updating client'
        : updateAvailable
          ? 'Update client?'
          : 'Re-apply client?'

  return (
    <Modal
      {...blockProps('modal.client-update')}
      opened={opened}
      onClose={() => (!running || done || failed ? onClose() : undefined)}
      title={title}
      centered
      size={logTail ? 'lg' : 'md'}
      onClick={(e) => e.stopPropagation()}
    >
      <Stack gap="md">
        {showProgress ? (
          <>
            <Text size="sm">
              {network}/{env}{' '}
              <Text span className="mono" fw={600}>
                {curLabel}
              </Text>
              {latestLabel ? (
                <>
                  {' '}
                  →{' '}
                  <Text span className="mono" fw={600} c="teal">
                    {latestLabel}
                  </Text>
                </>
              ) : null}
            </Text>
            <Stack gap={8}>
              {STEPS.map((s, i) => {
                const event = (info?.events || []).find((e) => (e.id || '').toLowerCase() === s.id)
                const isDone = done || (!failed && i < idx) || !!event
                const isActive = !done && !failed && i === idx
                return (
                  <Group key={s.id} gap="sm" align="flex-start" wrap="nowrap">
                    <ThemeIcon
                      size={22}
                      radius="xl"
                      variant="light"
                      color={failed && i === Math.max(0, idx) ? 'red' : isDone ? 'teal' : isActive ? 'yellow' : 'gray'}
                      mt={2}
                    >
                      {isDone && !isActive ? <IconCheck size={12} /> : <IconCircleDot size={12} />}
                    </ThemeIcon>
                    <Stack gap={0} style={{ minWidth: 0 }}>
                      <Text size="sm" fw={isActive ? 600 : 400} c={isActive ? undefined : 'dimmed'}>
                        {s.label}
                      </Text>
                      {event?.detail ? (
                        <Text size="xs" c="dimmed">
                          {hideURL(event.detail)}
                        </Text>
                      ) : null}
                    </Stack>
                  </Group>
                )
              })}
            </Stack>
            <Progress
              value={done ? 100 : pct}
              animated={running && !done && !failed}
              color={failed ? 'red' : done ? 'teal' : 'yellow'}
            />
            {failed ? (
              <>
                <Alert color="red" variant="light" icon={<IconAlertTriangle size={16} />}>
                  {detail || 'Update failed'}
                  {err && err !== detail ? ` — ${err}` : ''}
                </Alert>
              </>
            ) : done ? (
              <Alert color="teal" variant="light" icon={<IconCheck size={16} />}>
                Updated successfully. New client is running.
              </Alert>
            ) : (
              <Text size="sm" c="dimmed">
                {detail || 'Working…'}
              </Text>
            )}
            {logTail ? (
              <Stack gap={4}>
                <Text size="sm" fw={600}>
                  Logs
                </Text>
                <ScrollArea h={failed ? 220 : 160} type="auto" viewportRef={logViewport}>
                  <Code block style={{ whiteSpace: 'pre-wrap', fontSize: 11, wordBreak: 'break-all' }}>
                    {logTail}
                  </Code>
                </ScrollArea>
              </Stack>
            ) : null}
            <Group justify="flex-end">
              <Button variant="default" disabled={running && !done && !failed} onClick={onClose}>
                {done || failed ? 'Close' : 'Cancel'}
              </Button>
              {startErr ? (
                <Button color="orange" loading={requestBusy} onClick={onStart}>
                  Try again
                </Button>
              ) : null}
              {failed && !startErr && onRollback && previousVersion ? (
                <Button
                  color="orange"
                  leftSection={<IconHistory size={14} />}
                  loading={!!rollbackBusy}
                  onClick={onRollback}
                >
                  Enable {previousVersion}
                </Button>
              ) : null}
            </Group>
          </>
        ) : (
          <>
            <Text size="sm">
              {updateAvailable ? 'Update' : 'Re-download and re-install'}{' '}
              <Text span fw={700}>
                {network}/{env}
              </Text>
              .
            </Text>
            <Group gap="lg" wrap="wrap">
              <Stack gap={2}>
                <Text size="xs" c="dimmed" tt="uppercase">
                  Current
                </Text>
                <Text className="mono" fw={600}>
                  {curLabel}
                </Text>
              </Stack>
              <Stack gap={2}>
                <Text size="xs" c="dimmed" tt="uppercase">
                  New
                </Text>
                <Text className="mono" fw={600} c="teal">
                  {latestLabel || curLabel}
                </Text>
              </Stack>
            </Group>
            <Alert color="orange" variant="light" icon={<IconAlertTriangle size={16} />}>
              The node will be stopped, the client updated, then started again. Progress (stopped →
              updating → started) appears here from host webhooks.
            </Alert>
            <Group justify="flex-end">
              <Button variant="default" disabled={requestBusy} onClick={onClose}>
                Cancel
              </Button>
              <Button color="orange" loading={requestBusy} onClick={onStart}>
                {updateAvailable ? 'Update client' : 'Re-apply client'}
              </Button>
            </Group>
          </>
        )}
      </Stack>
    </Modal>
  )
}
