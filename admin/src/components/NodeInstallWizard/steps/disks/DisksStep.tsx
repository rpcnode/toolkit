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


export function DisksStep() {
  return <View {...useWizard()} />
}

function View({
    diskLoading,
    loadHostDisks,
    diskError,
    diskMounts,
    diskUnused,
    diskInsights,
    diskSummary,
    diskRules,
    diskRoles,
    diskLayout,
    diskRecommended,
    applyDiskLayout,
    diskSnapshotHint,
    diskNofile,
    diskRows,
    diskSaved,
    diskSaving,
    disksContinueReady,
    continueFromDisks,
    setUiStep,
    manualBackToPorts,
    manualBackToDisks,
    agentAckedStep,
    portsFetched,
    askAgentPorts,
    setPortsError,
    usingLiveNodeCatalog,
    portsLoading,
    portsConfirming,
    portsError,
    unsupported,
    networkId,
    wantsDiskLayout,
    workload,
    error,
    env,
}: WizardApi) {
  return (
    <>
          
<Box {...blockProps('node.detail.wizard.step.disks')}>
            <>
              {portsError && !usingLiveNodeCatalog && (
                <Alert color="red" title="Ports">
                  <Text size="sm">{portsError}</Text>
                </Alert>
              )}

              {!unsupported && (
                <HostDisksSection
                  defaultOpen={diskNofile?.ok === false || !!diskError}
                  nofile={diskNofile}
                  diskLoading={diskLoading}
                  network={networkId}
                  diskError={diskError}
                  mounts={diskMounts}
                  disks={diskRows}
                  unused={diskUnused}
                  insights={diskInsights}
                  summary={diskSummary}
                  refreshButton={
                    <Button
                      size="compact-sm"
                      variant="light"
                      leftSection={<IconRefresh size={14} />}
                      loading={portsLoading || diskLoading}
                      disabled={!workload?.server_id}
                      onClick={() => {
                        portsFetched.current = true
                        setPortsError(null)
                        void askAgentPorts(true)
                        void loadHostDisks()
                      }}
                    >
                      Refresh catalog
                    </Button>
                  }
                />
              )}

              {wantsDiskLayout && !unsupported && (
                <DiskLayoutSection title={diskLayoutTitleFor(networkId)}>
                  <DiskLayoutPanel
                    embedded
                    network={networkId}
                    env={env}
                    loading={diskLoading}
                    error={diskError}
                    mounts={diskMounts}
                    disks={diskRows}
                    unused={diskUnused}
                    roles={diskRoles}
                    recommended={diskRecommended}
                    layout={diskLayout}
                    rules={diskRules}
                    snapshotHint={diskSnapshotHint}
                    onChange={applyDiskLayout}
                    saved={diskSaved}
                    onUseRecommended={() => {
                      if (diskRecommended) applyDiskLayout(diskRecommended)
                      else void loadHostDisks()
                    }}
                  />
                </DiskLayoutSection>
              )}

              {error && (
                <Alert color="red" title="Setup failed">
                  <Text size="sm">{error}</Text>
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
                  disabled={diskSaving || portsConfirming}
                    onClick={() => {
                      manualBackToPorts.current = true
                      manualBackToDisks.current = false
                      setUiStep('ports')
                    }}
                >
                  Back
                </Button>
                <Button
                  className="node-install-wizard__actions-primary"
                  color="teal"
                  size="md"
                  rightSection={<IconArrowRight size={16} />}
                  loading={diskSaving || portsConfirming}
                  disabled={!disksContinueReady}
                  onClick={() => void continueFromDisks()}
                >
                  Continue
                </Button>
              </Group>
            </>
            </Box>
    
    </>
  )
}
