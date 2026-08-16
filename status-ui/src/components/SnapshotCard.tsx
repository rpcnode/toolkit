import {
  ActionIcon,
  Badge,
  Card,
  Group,
  Modal,
  Progress,
  Text,
  Title,
  Tooltip,
} from '@mantine/core'
import { IconFileText } from '@tabler/icons-react'
import { useState } from 'react'
import type { StatusPayload } from '../types'
import { StatusBadge } from './StatusBadge'
import { num, pct } from '../lib/format'
import { snapshotPhaseLabel } from '../lib/labels'
import { supportsSnapshotStep } from '../lib/network'
import { statusHonestlySynced } from '../lib/nodeLifecycle'

export type SnapshotUIMode = 'hidden' | 'badge' | 'full'

/** When to show snapshot UI for the selected env. */
export function snapshotUIMode(
  status: StatusPayload,
  network?: string | null,
): SnapshotUIMode {
  // Profile-driven: supported_steps / capabilities (bitcoin fallback until then).
  if (!supportsSnapshotStep(status, network)) return 'hidden'
  // Synced / online node card: snapshot is done — wizard owns the download UI.
  if (statusHonestlySynced(status)) return 'hidden'

  const snap = status.snapshot || {}
  if (snap.enabled === false) return 'hidden'

  const phase = (snap.phase || '').toLowerCase()
  const running = !!snap.wget_running || phase === 'download' || phase === 'extract' || phase === 'extracting'
  const failed = !!snap.failed || phase.includes('fail') || phase.includes('error')
  const ready = !!snap.ready
  const nodeOk = !!status.rpc?.reachable || !!status.connect?.ready
  const needsBootstrap = !ready && !nodeOk

  if (running || failed || needsBootstrap) return 'full'
  return 'hidden'
}

export function SnapshotReadyBadge({ status }: { status: StatusPayload }) {
  const [logOpen, setLogOpen] = useState(false)
  const snap = status.snapshot || {}
  return (
    <>
      <Group gap="xs">
        <Badge color="teal" variant="light">
          Snapshot ready
        </Badge>
        <LogIconButton onClick={() => setLogOpen(true)} />
      </Group>
      <SnapshotLogModal opened={logOpen} onClose={() => setLogOpen(false)} lines={snap.log_tail} />
    </>
  )
}

export function SnapshotCard({ status }: { status: StatusPayload }) {
  const snap = status.snapshot || {}
  const [logOpen, setLogOpen] = useState(false)
  const progress = pct(snap.pct)
  const label = snapshotPhaseLabel(snap.phase, {
    ready: !!snap.ready,
    wget: !!snap.wget_running,
    failed: !!snap.failed,
  })

  return (
    <>
      <Card>
        <Group justify="space-between" mb="sm" wrap="wrap">
          <Title order={4} c="dimmed" tt="uppercase" size="xs">
            Snapshot
          </Title>
          <Group gap="xs">
            <Badge color={snap.failed ? 'red' : snap.wget_running ? 'yellow' : snap.ready ? 'teal' : 'gray'} variant="light">
              {label}
            </Badge>
            <LogIconButton onClick={() => setLogOpen(true)} />
          </Group>
        </Group>
        <Text fw={750} size="2rem" style={{ letterSpacing: '-0.03em' }}>
          {String(snap.pct ?? '?')}
          {String(snap.pct ?? '').includes('%') ? '' : '%'}
        </Text>
        <Text c="dimmed" size="sm" mb="xs">
          {snap.eta || '—'} · {label}
        </Text>
        <Progress value={progress} color={snap.failed ? 'red' : 'teal'} size="md" radius="xl" mb="sm" />
        <Group justify="space-between">
          <Text size="sm" c="dimmed">
            ready
          </Text>
          <StatusBadge value={snap.ready ? 'active' : 'inactive'} />
        </Group>
        <Group justify="space-between" mt={6}>
          <Text size="sm" c="dimmed">
            output
          </Text>
          <Text size="sm">{status.output_size || '—'}</Text>
        </Group>
        {snap.detail && (
          <Text size="xs" c="dimmed" mt="sm">
            {snap.detail}
          </Text>
        )}
        {snap.url && (
          <Text size="xs" c="dimmed" mt={4} className="mono">
            {snap.url}
          </Text>
        )}
      </Card>
      <SnapshotLogModal opened={logOpen} onClose={() => setLogOpen(false)} lines={snap.log_tail} />
    </>
  )
}

function LogIconButton({ onClick }: { onClick: () => void }) {
  return (
    <Tooltip label="Snapshot log">
      <ActionIcon variant="subtle" color="gray" aria-label="Snapshot log" onClick={onClick}>
        <IconFileText size={16} />
      </ActionIcon>
    </Tooltip>
  )
}

function SnapshotLogModal({
  opened,
  onClose,
  lines,
}: {
  opened: boolean
  onClose: () => void
  lines?: string[]
}) {
  return (
    <Modal opened={opened} onClose={onClose} title="Snapshot log" size="lg" centered>
      <Text size="xs" c="dimmed" mb="sm">
        Tail of snapshot download log ({num(lines?.length ?? 0, 0)} lines)
      </Text>
      <div className="log-box">
        {(lines || []).length ? (
          (lines || []).map((line, i) => (
            <div className="line" key={i}>
              {line}
            </div>
          ))
        ) : (
          <Text c="dimmed" size="sm">
            (empty)
          </Text>
        )}
      </div>
    </Modal>
  )
}
