import { Alert, Badge, Box, Button, Group, Loader, Progress, Stack, Text, Title } from '@mantine/core'
import { IconArrowRight, IconPackage } from '@tabler/icons-react'
import { blockProps } from '../../../../lib/blockId'
import { useWizard, type WizardApi } from '../../wizardContext'

export function HostDepsStep() {
  return <View {...useWizard()} />
}

function View({
  hostDepsPlan,
  hostDepsPlanLoading,
  hostDepsPlanError,
  hostDepsProgress,
  hostDepsStarting,
  hostDepsJobId,
  startHostDepsInstall,
  continueFromHostDeps,
  goBackToClientsOrEarlier,
  workload,
  error,
}: WizardApi) {
  const items = hostDepsProgress?.items?.length
    ? hostDepsProgress.items
    : (hostDepsPlan?.deps || []).map((d: { id: string; label?: string }) => ({
        id: d.id,
        status: 'pending',
        detail: d.label || d.id,
      }))
  const ready =
    hostDepsProgress?.ready === true ||
    workload?.status === 'host_deps_complete' ||
    (hostDepsProgress == null && hostDepsPlan?.deps?.length === 0)
  const failed = hostDepsProgress?.failed === true || workload?.status === 'host_deps_error'
  const running =
    hostDepsStarting ||
    workload?.status === 'host_deps_running' ||
    (hostDepsProgress && !hostDepsProgress.ready && !hostDepsProgress.failed)

  return (
    <Box {...blockProps('node.detail.wizard.step.host-deps')}>
      <Stack gap="md">
        <Group justify="space-between">
          <Title order={3}>Host dependencies</Title>
          <Badge color={ready ? 'teal' : failed ? 'red' : 'cyan'} variant="light">
            {ready ? 'ready' : failed ? 'failed' : hostDepsProgress?.phase || workload?.status || 'pending'}
          </Badge>
        </Group>
        <Text c="dimmed" size="sm">
          Probe the host for packages from network.yml (plus common tools / Java), install anything
          missing, then continue. Progress updates while the agent installs each dependency.
        </Text>

        {hostDepsPlanLoading ? (
          <Group gap={8}>
            <Loader size="sm" />
            <Text size="sm" c="dimmed">
              Loading dependency plan…
            </Text>
          </Group>
        ) : null}

        {hostDepsPlanError ? (
          <Alert color="red" title="Plan failed">
            {hostDepsPlanError}
          </Alert>
        ) : null}

        {error ? (
          <Alert color="red" title="Error">
            {error}
          </Alert>
        ) : null}

        {items.length > 0 ? (
          <Stack gap={6}>
            {items.map((it: { id: string; status?: string; detail?: string }) => (
              <Group key={it.id} justify="space-between" wrap="nowrap">
                <Text size="sm" className="mono">
                  {it.id}
                </Text>
                <Badge
                  size="sm"
                  variant="light"
                  color={
                    it.status === 'present'
                      ? 'teal'
                      : it.status === 'failed'
                        ? 'red'
                        : it.status === 'installing'
                          ? 'cyan'
                          : 'gray'
                  }
                >
                  {it.status || 'pending'}
                </Badge>
              </Group>
            ))}
          </Stack>
        ) : !hostDepsPlanLoading ? (
          <Text size="sm" c="dimmed">
            No extra host packages for this network (common tools only).
          </Text>
        ) : null}

        {running && hostDepsProgress?.pct != null ? (
          <Stack gap={4}>
            <Progress value={hostDepsProgress.pct} animated />
            <Text size="xs" c="dimmed">
              {hostDepsProgress.detail || hostDepsProgress.current_id || 'Installing…'}
            </Text>
          </Stack>
        ) : null}

        {failed && hostDepsProgress?.error ? (
          <Alert color="red" title="Install failed">
            {hostDepsProgress.error}
          </Alert>
        ) : null}

        <Group justify="space-between">
          <Button variant="default" onClick={() => goBackToClientsOrEarlier()}>
            Back
          </Button>
          <Group gap="sm">
            {!ready ? (
              <Button
                leftSection={<IconPackage size={16} />}
                loading={hostDepsStarting}
                disabled={!!running && !!hostDepsJobId}
                onClick={() => void startHostDepsInstall()}
              >
                {failed ? 'Retry install' : 'Install missing'}
              </Button>
            ) : null}
            <Button
              rightSection={<IconArrowRight size={16} />}
              disabled={!ready}
              onClick={() => void continueFromHostDeps()}
            >
              Continue
            </Button>
          </Group>
        </Group>
      </Stack>
    </Box>
  )
}
