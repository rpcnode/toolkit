import {
  Alert,
  Box,
  Button,
  Group,
  Loader,
  Modal,
  Stack,
  Text,
} from '@mantine/core'
import { blockProps } from '../../lib/blockId'
import { InstallProgressModal } from '../InstallProgressModal'
import type { NodeInstallWizardProps } from './types'
import { WizardProvider } from './wizardContext'
import { WizardRail } from './WizardRail'
import { WizardActivityLog } from './WizardActivityLog'
import { PortsStep } from './steps/ports/PortsStep'
import { DisksStep } from './steps/disks/DisksStep'
import { NodeTypeStep } from './steps/nodeType/NodeTypeStep'
import { ClientsStep } from './steps/clients/ClientsStep'
import { SnapshotStep } from './steps/snapshot/SnapshotStep'
import { StartStep } from './steps/start/StartStep'
import { SyncStep } from './steps/sync/SyncStep'
import { DoneStep } from './steps/done/DoneStep'
import { useWizardOrchestration } from './useWizardOrchestration'
import type { StatusPayload } from '../../types'
import type { Workload } from '../../api'

export type { WizardStepId } from './types'

export function NodeInstallWizard(props: NodeInstallWizardProps) {
  const wizardApi = useWizardOrchestration(props)
  const {
    installOutcome,
    installModalOpen,
    installBusy,
    setInstallModalOpen,
    installError,
    error,
    stepPending,
    active,
    wantsNodeTypeStep,
    allowSnap,
    displayLog,
    displayLogJoined,
    wizardLogCopied,
    copyWizardLogs,
    wizardLogScroller,
    stopSnapshotOpen,
    stoppingSnapshot,
    setStopSnapshotOpen,
    confirmStopSnapshot,
    onRefresh,
  } = wizardApi

  return (
    <WizardProvider value={wizardApi}>
    <>
    {installOutcome === 'running' && !installModalOpen && !installBusy ? (
      <Alert
        color="yellow"
        variant="light"
        mb="sm"
        title="Install in progress"
      >
        <Group justify="space-between" wrap="wrap">
          <Text size="sm">Host logs keep updating in the background.</Text>
          <Button size="xs" variant="light" color="yellow" onClick={() => setInstallModalOpen(true)}>
            Show logs
          </Button>
        </Group>
      </Alert>
    ) : null}
    {installOutcome === 'fail' && !installModalOpen ? (
      <Alert color="red" variant="light" mb="sm" title="Install failed">
        <Group justify="space-between" wrap="wrap" align="flex-start">
          <Text size="sm" style={{ maxWidth: 620 }}>
            {installError || error || 'See the host log for the reason.'}
          </Text>
          <Button size="xs" variant="light" color="red" onClick={() => setInstallModalOpen(true)}>
            Show details
          </Button>
        </Group>
      </Alert>
    ) : null}
    <Box className="node-install-wizard" {...blockProps('node.detail.wizard')}>
      <WizardRail />
      <Box className="node-install-wizard__main" p={{ base: 'md', sm: 'xl' }}>
        <Stack gap="md">
          {stepPending && (
            <Stack align="center" gap="sm" py="xl">
              <Loader color="teal" />
              <Text c="dimmed" size="sm">
                Loading node status…
              </Text>
            </Stack>
          )}
          {!stepPending && active === 'ports' && <PortsStep />}
          {!stepPending && active === 'disks' && <DisksStep />}
          {!stepPending && active === 'node_type' && wantsNodeTypeStep && <NodeTypeStep />}
          {!stepPending && active === 'clients' && <ClientsStep />}
          {allowSnap && active === 'snapshot' && <SnapshotStep />}
          {active === 'start' && <StartStep />}
          {active === 'sync' && <SyncStep />}
          {active === 'done' && <DoneStep />}
          {displayLog.length > 0 &&
            active !== 'ports' &&
            active !== 'disks' &&
            active !== 'node_type' &&
            !installBusy && (
            <WizardActivityLog
              lines={displayLog}
              joined={displayLogJoined}
              copied={wizardLogCopied}
              onCopy={copyWizardLogs}
              scrollerRef={wizardLogScroller}
            />
          )}
        </Stack>
      </Box>
    </Box>

    <Modal
      opened={stopSnapshotOpen}
      onClose={() => !stoppingSnapshot && setStopSnapshotOpen(false)}
      title="Stop snapshot download?"
      centered
      {...blockProps('modal.wizard.stop-snapshot')}
    >
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          Stops aria2 / extract on the host. Downloaded bytes stay on disk — Start download again
          to resume or Retry snapshot after a failure. Does not wipe the datadir.
        </Text>
        <Group justify="flex-end" gap="sm">
          <Button
            variant="default"
            disabled={stoppingSnapshot}
            onClick={() => setStopSnapshotOpen(false)}
          >
            Cancel
          </Button>
          <Button color="red" loading={stoppingSnapshot} onClick={() => void confirmStopSnapshot()}>
            Stop download
          </Button>
        </Group>
      </Stack>
    </Modal>

    <InstallProgressModal
      opened={installModalOpen}
      onClose={() => setInstallModalOpen(false)}
      outcome={installOutcome ?? 'running'}
      error={installError || error}
      wizardLines={displayLog}
      onRefreshStatus={onRefresh}
    />
    </>
    </WizardProvider>
  )
}

/**
 * NODE SETUP until panel SQLite `status` is Active (or stopped/online after setup).
 * Height / tip / connect.ready must not drive this — only `workload.status`.
 */
export function needsInstallWizard(_status: StatusPayload | null, workload: Workload | null): boolean {
  const wlStatus = (workload?.status || '').toLowerCase()
  if (wlStatus === 'active' || wlStatus === 'online' || wlStatus === 'stopped') {
    return false
  }
  if (wlStatus === 'removing' || wlStatus === 'remove_error') {
    return false
  }
  return true
}
