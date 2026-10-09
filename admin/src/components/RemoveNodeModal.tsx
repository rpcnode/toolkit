import { Alert, Button, Group, Loader, Modal, Stack, Text, ThemeIcon } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconAlertTriangle, IconCheck, IconMinus, IconTrash, IconX } from '@tabler/icons-react'
import { useEffect, useRef, useState } from 'react'
import { api, type NodeRemovalProgress, type NodeRemovalStep } from '../api'
import { blockProps } from '../lib/blockId'
import { RemoveConfirmInput, removeConfirmPhrase, removePhraseMatches } from './RemoveConfirmInput'
import { RemoveNodeModePicker, removeSubmitLabel, type RemoveNodeMode } from './RemoveNodeModePicker'

const POLL_MS = 1200

function StepIcon({ status }: { status: string }) {
  if (status === 'running') return <Loader size={16} />
  if (status === 'done')
    return (
      <ThemeIcon size={20} radius="xl" color="teal" variant="light">
        <IconCheck size={13} />
      </ThemeIcon>
    )
  if (status === 'failed')
    return (
      <ThemeIcon size={20} radius="xl" color="red" variant="light">
        <IconX size={13} />
      </ThemeIcon>
    )
  if (status === 'skipped')
    return (
      <ThemeIcon size={20} radius="xl" color="gray" variant="light">
        <IconMinus size={13} />
      </ThemeIcon>
    )
  return (
    <ThemeIcon size={20} radius="xl" color="gray" variant="outline">
      <span />
    </ThemeIcon>
  )
}

function StepRow({ step }: { step: NodeRemovalStep }) {
  return (
    <Group gap="sm" wrap="nowrap" align="flex-start" data-step={step.id} data-status={step.status}>
      <div style={{ paddingTop: 1 }}>
        <StepIcon status={step.status} />
      </div>
      <Stack gap={0} style={{ minWidth: 0 }}>
        <Text size="sm" fw={600} c={step.status === 'pending' ? 'dimmed' : undefined}>
          {step.title}
        </Text>
        {step.detail ? (
          <Text size="xs" c={step.status === 'failed' ? 'red' : 'dimmed'} style={{ wordBreak: 'break-all' }}>
            {step.detail}
          </Text>
        ) : null}
      </Stack>
    </Group>
  )
}

/** Steps shown before the host answers for the first time. */
function placeholderSteps(mode: RemoveNodeMode): NodeRemovalStep[] {
  if (mode === 'panel') return [{ id: 'panel', title: 'Remove from the panel', status: 'running' }]
  return [
    { id: 'stop', title: 'Stop the node', status: 'running' },
    { id: 'files', title: 'Delete files', status: 'pending' },
    { id: 'service', title: 'Delete the service', status: 'pending' },
  ]
}

/**
 * One removal dialog for every page: pick what to delete, confirm by typing, then watch the host work
 * through stop → delete files → delete service. The panel row disappears when the host reports "done".
 */
export function RemoveNodeModal({
  opened,
  nodeId,
  network,
  env,
  retry,
  onClose,
  onRemoved,
  onChanged,
}: {
  opened: boolean
  nodeId: string
  network: string
  env: string
  /** The node is already marked removing / remove_error — a previous attempt did not finish. */
  retry?: boolean
  onClose: () => void
  /** The node row is gone (host finished, or panel-only removal). */
  onRemoved: () => void
  /** Something changed on the host or in the panel even though the node is still listed. */
  onChanged?: () => void
}) {
  const [mode, setMode] = useState<RemoveNodeMode>('wipe')
  const [typed, setTyped] = useState('')
  const [progress, setProgress] = useState<NodeRemovalProgress | null>(null)
  const [starting, setStarting] = useState(false)
  const [startError, setStartError] = useState('')
  const [pollError, setPollError] = useState('')
  const finished = useRef(false)

  const phrase = removeConfirmPhrase(network, env)
  const confirmed = removePhraseMatches(typed, phrase)
  const running = starting || (progress != null && !progress.done && !progress.failed)
  const succeeded = !!progress?.done
  const failed = !!progress?.failed

  useEffect(() => {
    if (!opened) return
    setMode('wipe')
    setTyped('')
    setProgress(null)
    setStarting(false)
    setStartError('')
    setPollError('')
    finished.current = false
  }, [opened, nodeId])

  // Poll while the host job is running.
  useEffect(() => {
    if (!opened || !progress || progress.done || progress.failed) return
    let stop = false
    const tick = async () => {
      try {
        const next = await api.nodeRemoveProgress(nodeId)
        if (stop) return
        setPollError('')
        setProgress(next)
      } catch (e) {
        if (stop) return
        setPollError(String((e as Error).message || e))
      }
    }
    const t = window.setInterval(() => void tick(), POLL_MS)
    return () => {
      stop = true
      window.clearInterval(t)
    }
  }, [opened, nodeId, progress])

  useEffect(() => {
    if (progress?.done && !finished.current) {
      finished.current = true
      notifications.show({
        color: 'teal',
        title: 'Node removed',
        message: `${network}/${env}`,
      })
      onRemoved()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [progress?.done])

  async function start() {
    setStarting(true)
    setStartError('')
    setProgress({ steps: placeholderSteps(mode) })
    try {
      const res = await api.nodeRemoveStart(nodeId, mode)
      setProgress(res)
    } catch (e) {
      setProgress(null)
      setStartError(String((e as Error).message || e))
      onChanged?.()
    } finally {
      setStarting(false)
    }
  }

  function removeFromPanelOnly() {
    void (async () => {
      try {
        await api.nodeRemoveStart(nodeId, 'panel')
        finished.current = true
        notifications.show({
          color: 'orange',
          title: 'Removed from panel',
          message: `${network}/${env} — the host was not changed`,
        })
        onRemoved()
      } catch (e) {
        setStartError(String((e as Error).message || e))
      }
    })()
  }

  const steps = progress?.steps ?? []
  const showSteps = steps.length > 0
  const canClose = !running

  return (
    <Modal
      {...blockProps('modal.remove-node')}
      opened={opened}
      onClose={() => (canClose ? onClose() : undefined)}
      title={showSteps ? `Removing ${network}/${env}` : 'Remove node?'}
      centered
      size="md"
      closeOnClickOutside={canClose}
      closeOnEscape={canClose}
    >
      <Stack gap="md">
        {!showSteps ? (
          <>
            <Text size="sm">
              Remove{' '}
              <Text span fw={700}>
                {network}/{env}
              </Text>
            </Text>
            {retry ? (
              <Alert color="orange" icon={<IconAlertTriangle size={16} />}>
                The previous removal did not finish — run it again. Steps that are already done are skipped.
              </Alert>
            ) : null}
            {startError ? (
              <Alert color="red" icon={<IconAlertTriangle size={16} />} title="Could not start">
                {startError}
              </Alert>
            ) : null}
            <RemoveNodeModePicker value={mode} onChange={setMode} disabled={starting} />
            {mode === 'wipe' && (
              <Alert color="red" icon={<IconAlertTriangle size={16} />} title="Destructive">
                Stops the node, deletes its files in /opt and on the selected disk, then deletes the service.
              </Alert>
            )}
            {mode === 'panel' && (
              <Alert color="orange" icon={<IconAlertTriangle size={16} />}>
                The node keeps running. Re-adding it later may hit busy ports until you remove it on the host.
              </Alert>
            )}
            <RemoveConfirmInput phrase={phrase} value={typed} onChange={setTyped} disabled={starting} />
            <Group justify="flex-end">
              <Button variant="default" onClick={onClose}>
                Cancel
              </Button>
              <Button
                color="red"
                loading={starting}
                disabled={!confirmed}
                leftSection={<IconTrash size={14} />}
                onClick={() => void start()}
              >
                {removeSubmitLabel(mode, !!retry)}
              </Button>
            </Group>
          </>
        ) : (
          <>
            <Stack gap="sm">
              {steps.map((s) => (
                <StepRow key={s.id} step={s} />
              ))}
            </Stack>
            {pollError ? (
              <Text size="xs" c="orange">
                Lost contact with the panel ({pollError}) — retrying…
              </Text>
            ) : null}
            {failed ? (
              <Alert color="red" icon={<IconAlertTriangle size={16} />} title="Removal stopped">
                {progress?.error || progress?.message || 'A step failed.'}
              </Alert>
            ) : null}
            {succeeded ? (
              <Alert color="teal" icon={<IconCheck size={16} />}>
                Everything is removed.
              </Alert>
            ) : null}
            <Group justify="flex-end">
              {failed ? (
                <>
                  <Button variant="default" onClick={removeFromPanelOnly}>
                    Remove from panel only
                  </Button>
                  <Button
                    color="red"
                    leftSection={<IconTrash size={14} />}
                    onClick={() => {
                      setProgress(null)
                      void start()
                    }}
                  >
                    Retry
                  </Button>
                </>
              ) : null}
              <Button variant="default" disabled={running} onClick={onClose}>
                {succeeded ? 'Close' : 'Cancel'}
              </Button>
            </Group>
          </>
        )}
      </Stack>
    </Modal>
  )
}
