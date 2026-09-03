import {
  Accordion,
  ActionIcon,
  Alert,
  Badge,
  Box,
  Button,
  Code,
  Group,
  Loader,
  Modal,
  Progress,
  Radio,
  Stack,
  Switch,
  Text,
  TextInput,
  ThemeIcon,
  Title,
  Tooltip,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import {
  IconAlertTriangle,
  IconArrowRight,
  IconCheck,
  IconCopy,
  IconDownload,
  IconPlayerPlay,
  IconPlayerStop,
  IconRefresh,
  IconX,
} from '@tabler/icons-react'
import { blockProps } from '../../../../lib/blockId'
import { copyText } from '../../../../lib/copyText'
import { formatSyncPct, pct } from '../../../../lib/format'
import { isSolanaNetwork } from '../../../../lib/network'
import {
  nodeReadyForOps,
  snapshotBlockMessage,
  snapReady,
  snapshotDownloadLive,
} from '../../../../lib/nodeLifecycle'
import { snapshotStartsViaNode } from '../../../../lib/setupLane'
import { DiskLayoutPanel, DiskLayoutSection, diskLayoutTitleFor } from '../../../DiskLayoutPanel'
import { HostDisksSection } from '../../../HostDiskInsights'
import { InstallOptionsPicker, installOptionLabel } from '../../../InstallOptionsPicker'
import { resolveSyncProgressPct } from '../../../SyncStatusCard'
import { WizardStepHelp } from '../../WizardStepHelp'
import {
  PORTS_CHECK_HELP,
  bindingForCatalogPortRole,
  catalogPortConfigEnabled,
  formatPortBusy,
  formatSnapshotBytes,
  formatSnapshotSpeed,
  formatSolanaBuildPendingMessage,
  heightProgressPct,
  isCheckPortsTimeout,
  optionalCatalogPorts,
  portConfigInstallOptionKey,
} from '../../utils'
import { useWizard, type WizardApi } from '../../wizardContext'


export function SyncStep() {
  return <View {...useWizard()} />
}

function View({
    status,
    workload,
    nodeHeight,
    nodeUnitRunning,
    nodeProcessBusy,
    nodeProcessError,
    controlNodeProcess,
    nodeLogLines,
    nodeLogPath,
    nodeLogScroller,
    syncingInWizard,
    syncProgress,
}: WizardApi) {
  return (
    <>
          
            <Box {...blockProps('node.detail.wizard.step.sync')}>
              <Stack gap="md">
                <Group justify="space-between">
                  <Title order={3}>Sync</Title>
                  <Group gap="sm">
                    <Button
                      size="xs"
                      variant="light"
                      color="red"
                      leftSection={<IconPlayerStop size={14} />}
                      loading={nodeProcessBusy}
                      disabled={nodeProcessBusy || !workload?.id || !nodeUnitRunning}
                      onClick={() => void controlNodeProcess('stop')}
                    >
                      Stop
                    </Button>
                    <Button
                      size="xs"
                      variant="light"
                      color="teal"
                      leftSection={<IconPlayerPlay size={14} />}
                      loading={nodeProcessBusy}
                      disabled={nodeProcessBusy || !workload?.id || nodeUnitRunning}
                      onClick={() => void controlNodeProcess('start')}
                    >
                      Start
                    </Button>
                    <Badge
                      color={nodeHeight?.status === 'active' || (nodeHeight?.behind ?? 1) <= 0 ? 'teal' : 'cyan'}
                      variant="light"
                    >
                      {nodeHeight?.status === 'active' || (nodeHeight?.behind != null && nodeHeight.behind <= 0)
                        ? 'at tip'
                        : syncingInWizard
                          ? 'catching up'
                          : 'sync'}
                    </Badge>
                  </Group>
                </Group>
                {nodeProcessError ? (
                  <Alert color="red" title="Process control failed">
                    <Text size="sm" style={{ overflowWrap: 'anywhere' }}>
                      {nodeProcessError}
                    </Text>
                  </Alert>
                ) : null}
                <Text c="dimmed" size="sm">
                  Node height vs public network tip. Status becomes active when caught up.
                  Use Stop / Start to restart the systemd unit on the host.
                </Text>
                <Stack gap={6}>
                  <Group justify="space-between" align="flex-end">
                    <Text size="sm" c="dimmed" tt="uppercase" fw={700}>
                      Sync progress
                    </Text>
                    <Text fw={800} size="xl" style={{ letterSpacing: '-0.03em' }} ta="right">
                      {nodeHeight?.behind != null && nodeHeight.behind > 0 ? (
                        <>
                          {Number(nodeHeight.behind).toLocaleString()}
                          <Text span size="sm" c="dimmed" fw={600} ml={6}>
                            behind
                          </Text>
                          {syncProgress != null ? (
                            <Text span size="sm" c="dimmed" fw={600} ml={8}>
                              · {formatSyncPct(syncProgress)}
                            </Text>
                          ) : null}
                        </>
                      ) : typeof status?.sync?.slots_behind === 'number' &&
                        status.sync.slots_behind > 0 &&
                        syncingInWizard ? (
                        <>
                          {status.sync.slots_behind.toLocaleString()}
                          <Text span size="sm" c="dimmed" fw={600} ml={6}>
                            behind
                          </Text>
                          {syncProgress != null ? (
                            <Text span size="sm" c="dimmed" fw={600} ml={8}>
                              · {formatSyncPct(syncProgress)} lag closed
                            </Text>
                          ) : null}
                        </>
                      ) : syncProgress != null ? (
                        formatSyncPct(syncProgress)
                      ) : syncingInWizard ? (
                        '…'
                      ) : (
                        '—'
                      )}
                    </Text>
                  </Group>
                  {nodeHeight ? (
                    <Text size="xs" c="dimmed" className="mono" ta="right">
                      node {Number(nodeHeight.height).toLocaleString()}
                      {nodeHeight.network_height != null ? (
                        <> · tip {Number(nodeHeight.network_height).toLocaleString()}</>
                      ) : null}
                    </Text>
                  ) : typeof status?.sync?.slot === 'number' &&
                    (typeof status?.sync?.cluster_slot === 'number' ||
                      typeof status?.sync?.headers === 'number') ? (
                    <Text size="xs" c="dimmed" className="mono" ta="right">
                      node {Number(status.sync.slot).toLocaleString()} · tip{' '}
                      {Number(
                        status.sync.cluster_slot ?? status.sync.headers ?? 0,
                      ).toLocaleString()}
                    </Text>
                  ) : null}
                  <Progress
                    value={
                      syncProgress != null
                        ? syncProgress
                        : nodeReadyForOps(status)
                          ? 100
                          : 0
                    }
                    animated={syncingInWizard && (syncProgress == null || syncProgress < 100)}
                    striped={syncingInWizard && (syncProgress == null || syncProgress < 100)}
                    size="xl"
                    radius="xl"
                    color={
                      syncProgress != null && syncProgress >= 100 && !syncingInWizard
                        ? 'teal'
                        : 'cyan'
                    }
                    style={{ minHeight: 14 }}
                  />
                  <Stack gap={6} {...blockProps('node.detail.wizard.step.sync.logs')}>
                    <Group justify="space-between" align="center">
                      <Text size="sm" c="dimmed" tt="uppercase" fw={700}>
                        Node logs
                      </Text>
                      <Group gap={6}>
                        {nodeLogPath ? (
                          <Text size="xs" c="dimmed" className="mono" lineClamp={1} maw={280}>
                            {nodeLogPath}
                          </Text>
                        ) : null}
                        <Tooltip label="Copy logs">
                          <ActionIcon
                            size="sm"
                            variant="light"
                            color="gray"
                            aria-label="Copy node logs"
                            disabled={nodeLogLines.length === 0}
                            onClick={() => {
                              void copyText(nodeLogLines.join('\n'))
                                .then(() =>
                                  notifications.show({
                                    color: 'teal',
                                    message: 'Logs copied',
                                    autoClose: 2000,
                                  }),
                                )
                                .catch(() =>
                                  notifications.show({
                                    color: 'red',
                                    message: 'Copy failed',
                                    autoClose: 2000,
                                  }),
                                )
                            }}
                          >
                            <IconCopy size={14} />
                          </ActionIcon>
                        </Tooltip>
                      </Group>
                    </Group>
                    <Box
                      ref={nodeLogScroller}
                      style={{
                        maxHeight: 240,
                        overflow: 'auto',
                        borderRadius: 8,
                        border: '1px solid var(--mantine-color-dark-4)',
                        background: 'var(--mantine-color-dark-7)',
                        padding: 8,
                      }}
                    >
                      {nodeLogLines.length > 0 ? (
                        <Code
                          block
                          className="mono"
                          style={{
                            whiteSpace: 'pre-wrap',
                            fontSize: 12,
                            background: 'transparent',
                            padding: 0,
                          }}
                        >
                          {nodeLogLines.join('\n')}
                        </Code>
                      ) : (
                        <Text size="sm" c="dimmed">
                          Waiting for host process log…
                        </Text>
                      )}
                    </Box>
                  </Stack>
                </Stack>
              </Stack>
            </Box>

          {/* done → parent hides wizard via needsInstallWizard; no Continue gate */}
    
    </>
  )
}
