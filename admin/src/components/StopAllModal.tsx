import { Alert, Button, Group, Loader, Modal, Progress, Stack, Text, ThemeIcon } from '@mantine/core'
import { IconAlertTriangle, IconCheck, IconPlayerStop, IconX } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { api, type StopAllItem, type StopAllProgress } from '../api'
import { blockProps } from '../lib/blockId'

const POLL_MS = 1500

function ItemIcon({ status }: { status: string }) {
  if (status === 'running' || status === 'pending') return <Loader size={16} />
  if (status === 'done')
    return (
      <ThemeIcon size={20} radius="xl" color="teal" variant="light">
        <IconCheck size={13} />
      </ThemeIcon>
    )
  return (
    <ThemeIcon size={20} radius="xl" color="red" variant="light">
      <IconX size={13} />
    </ThemeIcon>
  )
}

function ItemRow({ item }: { item: StopAllItem }) {
  return (
    <Group gap="sm" wrap="nowrap" align="flex-start" data-status={item.status}>
      <div style={{ paddingTop: 1 }}>
        <ItemIcon status={item.status} />
      </div>
      <Stack gap={0} style={{ minWidth: 0 }}>
        <Text size="sm" fw={600}>
          {(item.network || '').toUpperCase()} · {item.env}
          {item.name ? (
            <Text span size="xs" c="dimmed" fw={400}>
              {'  '}
              {item.name}
            </Text>
          ) : null}
        </Text>
        <Text size="xs" c={item.status === 'failed' ? 'red' : 'dimmed'} style={{ wordBreak: 'break-word' }}>
          {item.status === 'pending' ? 'waiting…' : item.detail || item.status}
        </Text>
      </Stack>
    </Group>
  )
}

/**
 * "Stop all": gracefully stops every running node on every server, all at once, and shows when it is
 * safe to switch the machine off. Closing the dialog does not interrupt the stop — it runs in the panel.
 */
export function StopAllModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const [progress, setProgress] = useState<StopAllProgress | null>(null)
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState('')

  // On open, pick up a run that is already going (opened from another tab / after a reload).
  useEffect(() => {
    if (!opened) return
    setError('')
    setStarting(false)
    let stop = false
    void api
      .stopAllProgress()
      .then((p) => {
        if (!stop) setProgress(p.running ? p : null)
      })
      .catch(() => {
        if (!stop) setProgress(null)
      })
    return () => {
      stop = true
    }
  }, [opened])

  useEffect(() => {
    if (!opened || !progress?.running) return
    let stop = false
    const t = window.setInterval(() => {
      void api
        .stopAllProgress()
        .then((p) => {
          if (!stop) setProgress(p)
        })
        .catch(() => undefined)
    }, POLL_MS)
    return () => {
      stop = true
      window.clearInterval(t)
    }
  }, [opened, progress?.running])

  async function start() {
    setStarting(true)
    setError('')
    try {
      setProgress(await api.stopAllNodes())
    } catch (e) {
      setError(String((e as Error).message || e))
    } finally {
      setStarting(false)
    }
  }

  const items = progress?.items ?? []
  const total = progress?.total ?? items.length
  const done = progress?.done ?? 0
  const failed = progress?.failed ?? 0
  const running = !!progress?.running
  const finished = progress != null && !running
  const allStopped = finished && failed === 0

  return (
    <Modal
      {...blockProps('modal.stop-all')}
      opened={opened}
      onClose={onClose}
      title="Stop all nodes"
      centered
      size="md"
    >
      <Stack gap="md">
        {progress == null ? (
          <>
            <Text size="sm">
              Gracefully stops every running node on all servers, all at once, so their databases are closed cleanly.
              Wait for the confirmation before you switch the PC or server off.
            </Text>
            <Text size="xs" c="dimmed">
              Large clients can take a few minutes to flush their database. Snapshot downloads are not touched.
            </Text>
            {error ? (
              <Alert color="red" icon={<IconAlertTriangle size={16} />}>
                {error}
              </Alert>
            ) : null}
            <Group justify="flex-end">
              <Button variant="default" onClick={onClose}>
                Cancel
              </Button>
              <Button color="red" loading={starting} leftSection={<IconPlayerStop size={14} />} onClick={() => void start()}>
                Stop all
              </Button>
            </Group>
          </>
        ) : (
          <>
            {total === 0 ? (
              <Alert color="teal" icon={<IconCheck size={16} />}>
                No node is running — nothing to stop. You can switch the machine off.
              </Alert>
            ) : (
              <>
                <Progress value={total ? ((done + failed) / total) * 100 : 100} color={failed ? 'red' : 'teal'} animated={running} />
                <Stack gap="sm" style={{ maxHeight: 360, overflowY: 'auto' }}>
                  {items.map((it) => (
                    <ItemRow key={it.node_id} item={it} />
                  ))}
                </Stack>
              </>
            )}
            {running ? (
              <Text size="xs" c="dimmed">
                Stopping {total - done - failed} of {total}… you can close this window, it keeps going.
              </Text>
            ) : null}
            {allStopped && total > 0 ? (
              <Alert color="teal" icon={<IconCheck size={16} />} title="All nodes stopped">
                It is safe to switch the machine off now.
              </Alert>
            ) : null}
            {finished && failed > 0 ? (
              <Alert color="red" icon={<IconAlertTriangle size={16} />} title="Some nodes did not stop">
                {failed} of {total} failed — do not switch off yet. Fix the cause (see above) and press Stop all again.
              </Alert>
            ) : null}
            <Group justify="flex-end">
              {finished && failed > 0 ? (
                <Button color="red" loading={starting} leftSection={<IconPlayerStop size={14} />} onClick={() => void start()}>
                  Stop all again
                </Button>
              ) : null}
              <Button variant="default" onClick={onClose}>
                {running ? 'Hide' : 'Close'}
              </Button>
            </Group>
          </>
        )}
      </Stack>
    </Modal>
  )
}
