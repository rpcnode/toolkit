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


export function ClientsStep() {
  return <View {...useWizard()} />
}

function View({
    clientsSyncing,
    clientsSynced,
    clientsError,
    clientsFiles,
    clientsPath,
    syncClientsToHost,
    continueFromClients,
    setClientsSynced,
    goBackToNodeTypeOrDisks,
    clientsAutoStarted,
}: WizardApi) {
  return (
    <>
          
<Box {...blockProps('node.detail.wizard.step.clients')}>
              <Stack gap="md">
                <Group justify="space-between">
                  <Title order={3}>Clients</Title>
                  <Badge
                    color={clientsSynced ? 'teal' : clientsError ? 'red' : 'cyan'}
                    variant="light"
                  >
                    {clientsSynced ? 'ready' : clientsSyncing ? 'syncing' : clientsError ? 'error' : 'pending'}
                  </Badge>
                </Group>
                <Text c="dimmed" size="sm">
                  Panel asks the host agent to download chain binaries into the node dir (same path
                  Snapshot / Start use). Required before Base snapshot and before Start on every
                  network.
                </Text>

                {clientsSyncing && (
                  <Alert color="cyan" title="Syncing to host" icon={<Loader size={16} />}>
                    <Text size="sm">Downloading client files via the host agent…</Text>
                  </Alert>
                )}

                {clientsError && (
                  <Alert color="red" title="Client sync failed">
                    <Text size="sm">{clientsError}</Text>
                    <Text size="sm" mt="xs" c="dimmed">
                      Ensure the client is downloaded in panel Clients, disk layout is saved, and the
                      agent can reach the panel.
                    </Text>
                  </Alert>
                )}

                {clientsSynced && !clientsError && (
                  <Alert color="teal" title="Client on host" icon={<IconCheck size={16} />}>
                    <Stack gap={4}>
                      {clientsPath && (
                        <Text size="sm">
                          Node dir: <Code>{clientsPath}</Code>
                        </Text>
                      )}
                      {clientsFiles.length > 0 ? (
                        <Text size="sm">
                          Files: {clientsFiles.map((f: string) => f.split('/').pop() || f).join(', ')}
                        </Text>
                      ) : (
                        <Text size="sm">Host sync finished — continue to the next step.</Text>
                      )}
                    </Stack>
                  </Alert>
                )}

                <Group
                  className="node-install-wizard__actions"
                  justify="space-between"
                  wrap="wrap"
                  mt="sm"
                  gap="sm"
                >
                  <Button
                    variant="default"
                    size="md"
                    className="node-install-wizard__actions-back"
                    disabled={clientsSyncing}
                    onClick={() => goBackToNodeTypeOrDisks()}
                  >
                    Back
                  </Button>
                  <Group gap="sm">
                    {(clientsError || clientsSynced) && (
                      <Button
                        variant="light"
                        size="md"
                        leftSection={<IconRefresh size={16} />}
                        loading={clientsSyncing}
                        onClick={() => {
                          clientsAutoStarted.current = true
                          setClientsSynced(false)
                          void syncClientsToHost()
                        }}
                      >
                        Retry sync
                      </Button>
                    )}
                    <Button
                      className="node-install-wizard__actions-primary"
                      color="teal"
                      size="md"
                      rightSection={<IconArrowRight size={16} />}
                      loading={clientsSyncing}
                      disabled={!clientsSynced || !!clientsError}
                      onClick={() => void continueFromClients()}
                    >
                      Continue
                    </Button>
                  </Group>
                </Group>
              </Stack>
            </Box>
    
    </>
  )
}
