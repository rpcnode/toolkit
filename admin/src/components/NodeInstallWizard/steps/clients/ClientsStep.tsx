import {
  Alert,
  Badge,
  Box,
  Button,
  Code,
  Group,
  Loader,
  Stack,
  Text,
  Title,
} from '@mantine/core'
import { IconArrowRight, IconCheck, IconRefresh } from '@tabler/icons-react'
import { blockProps } from '../../../../lib/blockId'
import { useWizard, type WizardApi } from '../../wizardContext'


export function ClientsStep() {
  return <View {...useWizard()} />
}

function View({
    clientsSyncing,
    clientsSynced,
    clientsError,
    clientsBuilding,
    clientsBuildStatus,
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
                    {clientsSynced
                      ? 'ready'
                      : clientsBuilding
                        ? 'compiling'
                        : clientsSyncing
                          ? 'syncing'
                          : clientsError
                            ? 'error'
                            : 'pending'}
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

                {clientsBuilding && !clientsSynced && (
                  <Alert color="cyan" title="Compiling the client for this host" icon={<Loader size={16} />}>
                    <Stack gap={4}>
                      <Text size="sm">{clientsBuilding}</Text>
                      {clientsBuildStatus && (
                        <Text size="sm" className="mono">
                          {clientsBuildStatus}
                        </Text>
                      )}
                      <Text size="sm" c="dimmed">
                        No binary exists for this server&apos;s CPU architecture yet, so a host agent of the same
                        architecture is building it (Docker). This can take hours the first time; the step continues
                        by itself when the client is ready. You can leave this page — the build keeps running.
                      </Text>
                    </Stack>
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
