import { Alert, Badge, Button, Card, Code, Group, Stack, Text, ThemeIcon } from '@mantine/core'
import { IconDownload, IconLockOpen, IconPlayerPlay } from '@tabler/icons-react'
import type { Workload } from '../api'

type Props = {
  workload?: Workload | null
  env: string
  /** Snapshot already ready on agent */
  snapshotReady?: boolean
  snapshotRunning?: boolean
  onDownloadSnapshot?: () => void
  snapBusy?: boolean
  canStartSnapshot?: boolean
}

/** Shared post-install checklist: open external ports + download snapshot. */
export function NodeSetupGuide({
  workload,
  env,
  snapshotReady,
  snapshotRunning,
  onDownloadSnapshot,
  snapBusy,
  canStartSnapshot,
}: Props) {
  const pub = workload?.public_port
  const agentPort = workload?.agent_port
  const nodeHttp = workload?.node_http_port
  const p2p = workload?.p2p_port
  const needSnap = !snapshotReady
  const network = (workload?.network || 'node').toLowerCase()

  return (
    <Card withBorder padding="md" radius="md">
      <Stack gap="md">
        <Group justify="space-between" align="flex-start">
          <div>
            <Text fw={700}>After provision · {env}</Text>
            <Text size="sm" c="dimmed">
              Finish these before the node is usable. Snapshot never starts by itself.
            </Text>
          </div>
          {needSnap ? (
            <Badge color="yellow" variant="light">
              needs snapshot
            </Badge>
          ) : (
            <Badge color="teal" variant="light">
              snapshot ready
            </Badge>
          )}
        </Group>

        {needSnap && (
          <Alert color="yellow" title="Download snapshot first" icon={<IconDownload size={16} />}>
            <Stack gap="sm">
              <Text size="sm">
                Chain data is not on disk yet. Download the snapshot <strong>before</strong> starting
                the node unit. Starting without it usually fails or syncs from genesis for a long
                time.
              </Text>
              {onDownloadSnapshot && (
                <Button
                  size="sm"
                  color="teal"
                  leftSection={<IconDownload size={14} />}
                  loading={snapBusy}
                  disabled={canStartSnapshot === false || snapshotRunning}
                  onClick={onDownloadSnapshot}
                >
                  {snapshotRunning ? 'Snapshot downloading…' : 'Download snapshot'}
                </Button>
              )}
            </Stack>
          </Alert>
        )}

        <Stack gap={6}>
          <Group gap={8}>
            <ThemeIcon size="sm" color="cyan" variant="light">
              <IconLockOpen size={14} />
            </ThemeIcon>
            <Text fw={600} size="sm">
              Open these ports yourself (firewall / security group)
            </Text>
          </Group>
          <Text size="xs" c="dimmed">
            Clients → Go RPC (sleep on update) → upstream node. Node Agent API is control-only.
            Open public / agent / P2P in the cloud security group (the agent does not change
            the host firewall).
          </Text>
          <Stack gap={4}>
            <PortRow
              port={pub}
              label="Go RPC"
              hint="Public proxy; sleep/maintenance on update"
              external
            />
            <PortRow
              port={agentPort}
              label="Node Agent API"
              hint="Panel / control API (separate port)"
              external
            />
            <PortRow
              port={nodeHttp}
              label="Upstream HTTP / RPC"
              hint="Loopback only (via Go)"
              external={false}
            />
            <PortRow port={p2p} label="P2P" hint={`${network} peer traffic`} external />
          </Stack>
        </Stack>

        {snapshotReady && (
          <Alert color="teal" title="Next: start node from panel" icon={<IconPlayerPlay size={16} />}>
            <Text size="sm">
              Use the install wizard / lifecycle controls in the panel. The agent starts the{' '}
              {network}/{env} unit after snapshot is ready — do not bypass with manual SSH start.
            </Text>
          </Alert>
        )}
      </Stack>
    </Card>
  )
}

function PortRow({
  port,
  label,
  hint,
  external,
}: {
  port?: number
  label: string
  hint: string
  external: boolean
}) {
  return (
    <Group justify="space-between" wrap="nowrap" gap="sm">
      <div>
        <Text size="sm" fw={500}>
          {label}
        </Text>
        <Text size="xs" c="dimmed">
          {hint}
        </Text>
      </div>
      <Group gap={6}>
        <Badge color={external ? 'cyan' : 'gray'} variant="light">
          {external ? 'external' : 'internal'}
        </Badge>
        <Code className="mono">{port != null && port > 0 ? String(port) : '—'}</Code>
      </Group>
    </Group>
  )
}
