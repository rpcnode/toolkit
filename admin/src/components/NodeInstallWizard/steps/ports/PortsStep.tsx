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
import { PortCatalogAccordion, PortLine } from './PortHelpers'
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


export function PortsStep() {
  return <View {...useWizard()} />
}

function View({
    portsLoading,
    unsupported,
    workload,
    env,
    needsAgentUpdate,
    agentVer,
    latestVer,
    updating,
    setUpdateOpen,
    updateOpen,
    confirmAgentUpdate,
    portsError,
    usingLiveNodeCatalog,
    reachNote,
    catalogPortsList,
    nodePortsChecking,
    nodePortsLiveFetched,
    checkHostPortsLive,
    portsOverallStatus,
    setKillTarget,
    confirmKillHolder,
    killTarget,
    killing,
    busyWhoisCmd,
    portsConfirming,
    goToDisksStep,
    retryLane,
    server,
    serverLabel,
    ports,
    status,
}: WizardApi) {
  return (
    <>
          
<Box {...blockProps('node.detail.wizard.step.ports')}>
            <>
              {portsLoading && (
                <Alert color="gray" title="Loading tip catalog…">
                  Fetching fixed ports for Go RPC, Node Agent API, upstream and P2P…
                </Alert>
              )}

              {unsupported && (
                <Alert
                  color="orange"
                  icon={<IconAlertTriangle size={16} />}
                  title={`${(workload?.network || 'network').toUpperCase()} · ${env} not supported`}
                >
                  <Stack gap="sm">
                    <Text size="sm">
                      {unsupported.message ||
                        `${workload?.network || 'network'}/${env} is not supported by this agent. Update the host agent to the latest version.`}
                    </Text>
                    <Group justify="space-between" align="center" wrap="wrap" gap="sm">
                      <Group gap={6} align="center" wrap="nowrap">
                        <Text size="xs" c="dimmed" tt="uppercase" style={{ letterSpacing: 0.4 }}>
                          Agent
                        </Text>
                        <Text
                          size="sm"
                          fw={600}
                          className="mono"
                          c={needsAgentUpdate ? 'orange' : 'dimmed'}
                          title={
                            latestVer
                              ? `Installed ${agentVer || '?'} — CDN latest ${latestVer}`
                              : 'Installed agent version'
                          }
                        >
                          {agentVer || '—'}
                        </Text>
                        {latestVer ? (
                          <Badge color="orange" variant="light" size="sm">
                            → {latestVer}
                          </Badge>
                        ) : null}
                      </Group>
                      <Tooltip
                        label={
                          latestVer
                            ? `Update agent ${agentVer || '?'} → ${latestVer}`
                            : 'Update host agent from CDN'
                        }
                      >
                        <span>
                          <ActionIcon
                            color="orange"
                            variant="light"
                            size="lg"
                            loading={updating}
                            disabled={updating || !workload?.server_id}
                            aria-label="Update agent"
                            onClick={() => setUpdateOpen(true)}
                          >
                            <IconDownload size={16} />
                          </ActionIcon>
                        </span>
                      </Tooltip>
                    </Group>
                  </Stack>
                </Alert>
              )}

              {portsError && !unsupported && !usingLiveNodeCatalog && (
                <Alert
                  color="red"
                  title={isCheckPortsTimeout(portsError) ? 'Check ports timed out' : 'Ports busy'}
                >
                  <Stack gap={8}>
                    <Text size="sm">{portsError}</Text>
                    {busyWhoisCmd && !isCheckPortsTimeout(portsError) ? (
                      <>
                        <Text size="sm">
                          On the Server host (SSH) — program name + cmdline:
                        </Text>
                        <Code
                          block
                          className="mono"
                          style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}
                        >
                          {busyWhoisCmd}
                        </Code>
                        <Button
                          size="xs"
                          variant="light"
                          leftSection={<IconCopy size={14} />}
                          onClick={() => {
                            void copyText(busyWhoisCmd)
                              .then(() => {
                                notifications.show({
                                  color: 'blue',
                                  message: 'Whois commands copied',
                                  autoClose: 2000,
                                })
                              })
                              .catch(() => {
                                notifications.show({
                                  color: 'red',
                                  message: 'Copy failed',
                                  autoClose: 2000,
                                })
                              })
                          }}
                        >
                          Copy commands
                        </Button>
                      </>
                    ) : null}
                    <Button
                      size="xs"
                      variant="light"
                      color="red"
                      style={{ alignSelf: 'flex-start' }}
                      leftSection={<IconRefresh size={14} />}
                      disabled={portsConfirming}
                      onClick={() => retryLane('ports')}
                    >
                      Retry ports
                    </Button>
                  </Stack>
                </Alert>
              )}

              {!unsupported && (
                <>
                  <PortCatalogAccordion
                    ports={catalogPortsList}
                    status={portsOverallStatus}
                    onKill={(p) => setKillTarget(p)}
                  />
                  {usingLiveNodeCatalog ? (
                    <Group justify="flex-start">
                      <Button
                        size="xs"
                        variant="light"
                        color="teal"
                        leftSection={<IconRefresh size={14} />}
                        loading={nodePortsChecking}
                        disabled={nodePortsChecking || portsConfirming}
                        onClick={() => {
                          if (!workload?.server_id) return
                          nodePortsLiveFetched.current = false
                          void checkHostPortsLive()
                        }}
                      >
                        Re-check ports
                      </Button>
                    </Group>
                  ) : null}
                  {nodePortsChecking ? (
                    <Alert color="gray" variant="light" title="Checking ports on the host…">
                      Probing catalog ports against the host agent.
                    </Alert>
                  ) : null}
                  {reachNote && (
                    <Alert color="red" title="Panel cannot reach these ports">
                      {reachNote} Install is blocked until public / agent / P2P are reachable from
                      outside (cloud SG + host firewall). XRPL peers need inbound TCP 51235.
                    </Alert>
                  )}
                </>
              )}

              <Group
                className="node-install-wizard__actions"
                justify="flex-end"
                wrap="wrap"
                mt="sm"
                gap="sm"
              >
                <Button
                  className="node-install-wizard__actions-primary"
                  color="teal"
                  size="md"
                  rightSection={<IconArrowRight size={16} />}
                  disabled={
                    !!unsupported ||
                    portsLoading ||
                    nodePortsChecking ||
                    (usingLiveNodeCatalog && portsOverallStatus !== 'ok') ||
                    (!usingLiveNodeCatalog && (!ports?.public_port || !ports?.agent_port)) ||
                    !!reachNote ||
                    portsOverallStatus === 'fail'
                  }
                  onClick={goToDisksStep}
                >
                  Continue
                </Button>
              </Group>

              <Modal
                {...blockProps('modal.wizard.update-agent')}
                opened={updateOpen}
                onClose={() => (!updating ? setUpdateOpen(false) : undefined)}
                title="Update host agent?"
                centered
              >
                <Stack gap="md">
                  <Alert color="yellow" icon={<IconAlertTriangle size={16} />}>
                    Downloads <strong>api-agent + system-agent</strong> from CDN and restarts their
                    systemd units. Brief disconnect possible. Then re-check ports for{' '}
                    <Code>
                      {workload?.network || '?'}/{env}
                    </Code>
                    .
                  </Alert>
                  <Text size="sm">
                    <Text span fw={700}>
                      {serverLabel || server?.name || server?.id || 'Server'}
                    </Text>
                    : <Code className="mono">{agentVer || '?'}</Code>
                    {' → '}
                    <Code className="mono">{latestVer || 'CDN latest'}</Code>
                  </Text>
                  <Group justify="flex-end">
                    <Button variant="default" onClick={() => setUpdateOpen(false)}>
                      Cancel
                    </Button>
                    <Button
                      color="teal"
                      leftSection={<IconDownload size={14} />}
                      loading={updating}
                      onClick={() => void confirmAgentUpdate()}
                    >
                      Confirm update + restart
                    </Button>
                  </Group>
                </Stack>
              </Modal>

              <Modal
                {...blockProps('modal.wizard.kill-port')}
                opened={!!killTarget}
                onClose={() => (!killing ? setKillTarget(null) : undefined)}
                title="Kill process on this port?"
                centered
              >
                <Stack gap="md">
                  <Alert color="red" icon={<IconAlertTriangle size={16} />}>
                    Sends SIGTERM, then SIGKILL, on the Server host. Tip agent, sshd, and this
                    node&apos;s own units are refused.
                  </Alert>
                  <Text size="sm">
                    <Code className="mono">:{killTarget?.port}</Code>
                    {killTarget?.label ? ` · ${killTarget.label}` : ''}
                  </Text>
                  <Text size="sm">
                    <Text span fw={700}>
                      {killTarget?.comm || 'unknown'}
                    </Text>
                    {killTarget?.pid ? (
                      <>
                        {' '}
                        pid <Code className="mono">{killTarget.pid}</Code>
                      </>
                    ) : null}
                    {killTarget?.unit ? (
                      <>
                        {' '}
                        · <Code className="mono">{killTarget.unit}</Code>
                      </>
                    ) : null}
                  </Text>
                  {killTarget?.cmdline ? (
                    <Code block className="mono" style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
                      {killTarget.cmdline}
                    </Code>
                  ) : null}
                  {killTarget && killTarget.killable === false ? (
                    <Alert color="orange">
                      {killTarget.kill_blocked || 'Agent will not kill this process'}
                    </Alert>
                  ) : null}
                  <Group justify="flex-end">
                    <Button variant="default" disabled={killing} onClick={() => setKillTarget(null)}>
                      Cancel
                    </Button>
                    <Button
                      color="red"
                      leftSection={<IconX size={14} />}
                      loading={killing}
                      disabled={killTarget?.killable !== true}
                      onClick={() => void confirmKillHolder()}
                    >
                      Kill process
                    </Button>
                  </Group>
                </Stack>
              </Modal>
            </>
            </Box>
    
    </>
  )
}
