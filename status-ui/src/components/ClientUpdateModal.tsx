import { Alert, Button, Group, Modal, Progress, Stack, Text, ThemeIcon } from '@mantine/core'
import {
  IconAlertTriangle,
  IconCheck,
  IconCircleDot,
  IconPlayerStop,
} from '@tabler/icons-react'
import type { ClientUpdateInfo } from '../api'
import { formatClientVersion } from '../lib/format'

const STEPS = [
  { id: 'check', label: 'Catalog' },
  { id: 'download', label: 'Download' },
  { id: 'install', label: 'Install' },
  { id: 'done', label: 'Done' },
] as const

function hideURL(s: string): string {
  return String(s || '')
    .replace(/https?:\/\/\S+/gi, '')
    .replace(/\s{2,}/g, ' ')
    .trim()
}

function stepIndex(step?: string | null): number {
  const s = (step || '').toLowerCase()
  const i = STEPS.findIndex((x) => x.id === s)
  return i
}

type Props = {
  opened: boolean
  onClose: () => void
  network: string
  env: string
  current?: string
  latest?: string
  updateAvailable?: boolean
  allowed: boolean
  info?: ClientUpdateInfo | null
  started: boolean
  requestBusy: boolean
  onStop?: () => void
  onStart: () => void
}

export function ClientUpdateModal({
  opened,
  onClose,
  network,
  env,
  current,
  latest,
  updateAvailable,
  allowed,
  info,
  started,
  requestBusy,
  onStop,
  onStart,
}: Props) {
  const phase = (info?.phase || '').toLowerCase()
  const step = (info?.step || '').toLowerCase()
  const running = started || phase === 'updating'
  const failed = started && phase === 'error'
  const done =
    !failed &&
    started &&
    (step === 'done' || (phase === 'idle' && Number(info?.pct) >= 99))
  const showProgress = allowed && (running || done || failed)
  const pct = Math.max(0, Math.min(100, Number(info?.pct) || 0))
  const idx = failed ? -1 : done ? STEPS.length - 1 : Math.max(0, stepIndex(step))
  const detail = hideURL(info?.detail || '')
  const err = hideURL(info?.last_error || '')
  const curLabel = formatClientVersion(current || '') || '—'
  const latestLabel = formatClientVersion(latest || '')
  const title = !allowed
    ? 'Stop the node first'
    : failed
      ? 'Client update failed'
      : done
        ? 'Client updated'
        : running
          ? 'Updating client'
          : updateAvailable
            ? 'Update client?'
            : 'Re-apply latest client?'

  return (
    <Modal
      opened={opened}
      onClose={() => (!running || done || failed ? onClose() : undefined)}
      title={title}
      centered
      onClick={(e) => e.stopPropagation()}
    >
      <Stack gap="md">
        {!allowed ? (
          <>
            <Alert color="yellow" variant="light" icon={<IconAlertTriangle size={16} />}>
              Stop the node first (Stop), then update the client. Start brings the new binary up.
            </Alert>
            <Group justify="flex-end">
              <Button variant="default" onClick={onClose}>
                Close
              </Button>
              {onStop ? (
                <Button color="yellow" leftSection={<IconPlayerStop size={14} />} onClick={onStop}>
                  Stop
                </Button>
              ) : null}
            </Group>
          </>
        ) : showProgress ? (
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
                const isDone = done || (!failed && i < idx)
                const isActive = !done && !failed && i === idx
                return (
                  <Group key={s.id} gap="sm">
                    <ThemeIcon
                      size={22}
                      radius="xl"
                      variant="light"
                      color={isDone ? 'teal' : isActive ? 'yellow' : 'gray'}
                    >
                      {isDone ? <IconCheck size={12} /> : <IconCircleDot size={12} />}
                    </ThemeIcon>
                    <Text size="sm" fw={isActive ? 600 : 400} c={isActive ? undefined : 'dimmed'}>
                      {s.label}
                    </Text>
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
              <Alert color="red" variant="light" icon={<IconAlertTriangle size={16} />}>
                {detail || 'Update failed'}
                {err && err !== detail ? ` — ${err}` : ''}
              </Alert>
            ) : done ? (
              <Alert color="teal" variant="light" icon={<IconCheck size={16} />}>
                Updated successfully. Start to run the new client.
              </Alert>
            ) : (
              <Text size="sm" c="dimmed">
                {detail || 'Working…'}
              </Text>
            )}
            <Group justify="flex-end">
              <Button variant="default" disabled={running && !done && !failed} onClick={onClose}>
                {done || failed ? 'Close' : 'Cancel'}
              </Button>
            </Group>
          </>
        ) : (
          <>
            <Text size="sm">
              {updateAvailable ? 'Update' : 'Re-download and re-install'}{' '}
              <Text span fw={700}>
                {network}/{env}
              </Text>{' '}
              client{' '}
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
              .
            </Text>
            {!updateAvailable ? (
              <Text size="sm" c="dimmed">
                Already on latest — re-install only. Node stays stopped until Start.
              </Text>
            ) : null}
            <Alert color="orange" variant="light" icon={<IconAlertTriangle size={16} />}>
              Node is stopped. Replace the client only — then Start to bring it up.
            </Alert>
            <Group justify="flex-end">
              <Button variant="default" disabled={requestBusy} onClick={onClose}>
                Cancel
              </Button>
              <Button color="orange" loading={requestBusy} onClick={onStart}>
                {updateAvailable ? 'Update client' : 'Re-apply latest'}
              </Button>
            </Group>
          </>
        )}
      </Stack>
    </Modal>
  )
}
