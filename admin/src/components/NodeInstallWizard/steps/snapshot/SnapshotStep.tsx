import {
  Alert,
  Badge,
  Box,
  Button,
  Code,
  Group,
  Loader,
  Progress,
  Radio,
  Stack,
  Text,
  Title,
} from '@mantine/core'
import { IconArrowRight, IconDownload, IconPlayerStop } from '@tabler/icons-react'
import { blockProps } from '../../../../lib/blockId'
import { InstallOptionsPicker } from '../../../InstallOptionsPicker'
import { formatSnapshotBytes, formatSnapshotSpeed } from '../../utils'
import { useWizard, type WizardApi } from '../../wizardContext'


export function SnapshotStep() {
  return <View {...useWizard()} />
}

function View({
    snapshotViaNode,
    snapshotPlan,
    snapshotPlanLoading,
    snapshotPlanError,
    snapshotOptionGroups,
    installOptions,
    setInstallOptions,
    persistSnapshotType,
    snapshotSourceId,
    setSnapshotSourceId,
    snapshotSpeedById,
    snapshotProgress,
    snapshotDownloadReady,
    snapshotIdleAfterStop,
    snapshotStarting,
    startPanelSnapshotDownload,
    stoppingSnapshot,
    setStopSnapshotOpen,
    stopSnapshotPolling,
    continueFromSnapshot,
    goBackToClientsOrEarlier,
    workload,
    error,
}: WizardApi) {
  return (
    <>
          
<Box {...blockProps('node.detail.wizard.step.snapshot')}>
              <Stack gap="md">
                <Group justify="space-between">
                  <Title order={3}>Snapshot</Title>
                  <Badge color="cyan" variant="light">
                    {snapshotProgress?.ready || workload?.status === 'snapshot_complete'
                      ? 'ready'
                      : snapshotProgress?.phase || workload?.status || 'pending'}
                  </Badge>
                </Group>
                <Text c="dimmed" size="sm">
                  Pick Full / Lite / Archive (per network), then download to the Disks path. CDN
                  stores archives under /snapshots/{'{network}'}/{'{env}'}/{'{type}'}/.
                </Text>

                {snapshotOptionGroups.length > 0 ? (
                  <InstallOptionsPicker
                    groups={snapshotOptionGroups}
                    value={installOptions}
                    disabled={
                      snapshotStarting ||
                      stoppingSnapshot ||
                      workload?.status === 'snapshot_running' ||
                      snapshotProgress?.phase === 'download' ||
                      snapshotProgress?.phase === 'extract' ||
                      (snapshotProgress?.ready === true && !snapshotIdleAfterStop) ||
                      (workload?.status === 'snapshot_complete' && !snapshotIdleAfterStop)
                    }
                    onChange={(next) => {
                      setInstallOptions(next)
                      const typeId = (next.snapshot || '').trim()
                      if (!typeId) return
                      void persistSnapshotType(typeId)
                    }}
                  />
                ) : null}

                {snapshotPlanLoading ? (
                  <Group gap={8}>
                    <Loader size="sm" />
                    <Text size="sm" c="dimmed">
                      Loading snapshot source…
                    </Text>
                  </Group>
                ) : null}

                {snapshotPlanError ? (
                  <Alert color="red" title="Snapshot plan">
                    <Text size="sm">{snapshotPlanError}</Text>
                  </Alert>
                ) : null}

                {snapshotPlan?.sources && snapshotPlan.sources.length > 0 ? (
                  <Stack gap="xs">
                    <Text size="sm" fw={600}>
                      Download source
                    </Text>
                    <Radio.Group
                      value={snapshotSourceId}
                      onChange={setSnapshotSourceId}
                      name="snapshot-source"
                    >
                      <Stack gap="sm">
                        {snapshotPlan.sources.map((src: any) => {
                          const speed = snapshotSpeedById[src.id]
                          return (
                          <Box
                            key={src.id}
                            p="sm"
                            style={{
                              border: '1px solid var(--mantine-color-dark-4)',
                              borderRadius: 8,
                              opacity: src.available ? 1 : 0.72,
                            }}
                          >
                            <Radio
                              value={src.id}
                              disabled={!src.available}
                              label={
                                <Group gap="xs" wrap="wrap">
                                  <Text size="sm" fw={600}>
                                    {src.label}
                                  </Text>
                                  <Badge
                                    size="xs"
                                    color={src.available ? 'teal' : 'gray'}
                                    variant="light"
                                  >
                                    {src.available ? 'Available' : 'Unavailable'}
                                  </Badge>
                                  {speed?.loading ? (
                                    <Group gap={4}>
                                      <Loader size={10} />
                                      <Text size="xs" c="dimmed">
                                        Probing speed…
                                      </Text>
                                    </Group>
                                  ) : speed?.bytes_per_sec ? (
                                    <Badge size="xs" color="blue" variant="light">
                                      {formatSnapshotSpeed(speed.bytes_per_sec)} from host
                                    </Badge>
                                  ) : speed?.error ? (
                                    <Text size="xs" c="red">
                                      {speed.error}
                                    </Text>
                                  ) : speed?.available === false ? (
                                    <Text size="xs" c="dimmed">
                                      Unreachable from host
                                    </Text>
                                  ) : null}
                                  {src.version ? (
                                    <Badge size="xs" variant="outline">
                                      {src.version}
                                    </Badge>
                                  ) : null}
                                  {src.size_bytes ? (
                                    <Text size="xs" c="dimmed">
                                      {formatSnapshotBytes(src.size_bytes)}
                                    </Text>
                                  ) : null}
                                </Group>
                              }
                            />
                            {src.detail ? (
                              <Text size="xs" c="dimmed" mt={4} ml={28}>
                                {src.detail}
                              </Text>
                            ) : null}
                            {speed?.detail && !speed.loading ? (
                              <Text size="xs" c="dimmed" mt={4} ml={28}>
                                {speed.detail}
                              </Text>
                            ) : null}
                            {src.url ? (
                              <Code
                                block
                                className="mono"
                                mt={6}
                                ml={28}
                                style={{ whiteSpace: 'pre-wrap', fontSize: 11 }}
                              >
                                {src.url}
                              </Code>
                            ) : null}
                          </Box>
                          )
                        })}
                      </Stack>
                    </Radio.Group>
                    {snapshotPlan.stream_unpack ? (
                      <Text size="xs" c="dimmed">
                        Stream unpack: yes (tar extracts while downloading)
                      </Text>
                    ) : null}
                    <Text size="sm" fw={600} mt="xs">
                      Destination on host
                    </Text>
                    <Code block className="mono" style={{ fontSize: 12 }}>
                      {snapshotPlan.dest_dir || 'Pick disk layout first'}
                    </Code>
                  </Stack>
                ) : snapshotViaNode && snapshotPlan ? (
                  <Stack gap="xs">
                    <Alert color="blue" title="Via-node snapshot">
                      <Text size="sm">
                        This network has no toolkit CDN archive. The validator downloads the cluster
                        snapshot itself after Start (Agave ExtraStep). Confirm the destination, then
                        Continue.
                      </Text>
                    </Alert>
                    <Text size="sm" fw={600} mt="xs">
                      Destination on host
                    </Text>
                    <Code block className="mono" style={{ fontSize: 12 }}>
                      {snapshotPlan.dest_dir || 'Pick disk layout first'}
                    </Code>
                  </Stack>
                ) : snapshotPlan ? (
                  <Stack gap="xs">
                    <Text size="sm" fw={600}>
                      Download source
                    </Text>
                    <Code block className="mono" style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
                      {[
                        snapshotPlan.url || 'URL not resolved',
                        snapshotPlan.source ? `mirror: ${snapshotPlan.source}` : '',
                        snapshotPlan.version ? `version: ${snapshotPlan.version}` : '',
                        snapshotPlan.size_bytes
                          ? `size: ${formatSnapshotBytes(snapshotPlan.size_bytes)}`
                          : '',
                        snapshotPlan.stream_unpack ? 'stream unpack: yes' : '',
                      ]
                        .filter(Boolean)
                        .join('\n')}
                    </Code>
                    <Text size="sm" fw={600} mt="xs">
                      Destination on host
                    </Text>
                    <Code block className="mono" style={{ fontSize: 12 }}>
                      {snapshotPlan.dest_dir || 'Pick disk layout first'}
                    </Code>
                  </Stack>
                ) : null}

                {(snapshotStarting ||
                  snapshotProgress?.phase === 'download' ||
                  snapshotProgress?.phase === 'extract' ||
                  snapshotProgress?.phase === 'complete' ||
                  snapshotProgress?.phase === 'failed' ||
                  snapshotProgress?.phase === 'aborted' ||
                  workload?.status === 'snapshot_running' ||
                  workload?.status === 'snapshot_complete' ||
                  workload?.status === 'snapshot_error') ? (
                  <Stack gap="xs" mt="sm" {...blockProps('node.install.snapshot.progress')}>
                    {!snapshotProgress?.ready || snapshotProgress?.phase === 'failed' ? (
                      <>
                        <Progress
                          value={Math.max(0, Math.min(100, Number(snapshotProgress?.pct ?? 0)))}
                          animated={
                            !snapshotProgress?.ready &&
                            !snapshotProgress?.failed &&
                            (snapshotProgress?.pct == null || Number(snapshotProgress.pct) < 100)
                          }
                          striped
                          size="lg"
                          color={snapshotProgress?.failed ? 'red' : undefined}
                        />
                        <Text size="sm" c="dimmed">
                          {snapshotProgress?.detail ||
                            (snapshotStarting
                              ? 'Starting download on host…'
                              : 'Waiting for progress…')}
                        </Text>
                      </>
                    ) : (
                      <Text size="sm" c="teal">
                        {snapshotProgress?.detail || 'Snapshot ready'}
                      </Text>
                    )}
                    <Text size="xs" fw={600} c="dimmed" tt="uppercase">
                      Download log
                    </Text>
                    <div
                      className="log-box"
                      style={{ maxHeight: 240, overflow: 'auto' }}
                      ref={(el) => {
                        if (el) el.scrollTop = el.scrollHeight
                      }}
                    >
                      {(snapshotProgress?.log_tail || []).length ? (
                        (snapshotProgress?.log_tail || []).map((line: string, i: number) => (
                          <div className="line" key={`${i}-${line.slice(0, 32)}`}>
                            {line}
                          </div>
                        ))
                      ) : (
                        <Text c="dimmed" size="sm">
                          {snapshotStarting || workload?.status === 'snapshot_running'
                            ? 'Waiting for host log lines…'
                            : 'No download log yet'}
                        </Text>
                      )}
                    </div>
                  </Stack>
                ) : null}

                {error ? (
                  <Alert color="red" title="Snapshot error">
                    <Text
                      size="sm"
                      className="mono"
                      style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}
                    >
                      {error}
                    </Text>
                  </Alert>
                ) : null}

                <Group justify="space-between" wrap="wrap" mt="sm">
                  <Button
                    variant="default"
                    onClick={() => {
                      stopSnapshotPolling()
                      goBackToClientsOrEarlier()
                    }}
                  >
                    Back
                  </Button>
                  <Group gap="sm">
                    {!(
                      snapshotProgress?.ready === true ||
                      workload?.status === 'snapshot_complete'
                    ) &&
                    !snapshotIdleAfterStop &&
                    (snapshotStarting ||
                      workload?.status === 'snapshot_running' ||
                      snapshotProgress?.phase === 'download' ||
                      snapshotProgress?.phase === 'extract') ? (
                      <Button
                        color="red"
                        variant="light"
                        leftSection={<IconPlayerStop size={16} />}
                        loading={stoppingSnapshot}
                        disabled={stoppingSnapshot}
                        onClick={() => setStopSnapshotOpen(true)}
                      >
                        Stop
                      </Button>
                    ) : null}
                    {!snapshotViaNode ? (
                      <Button
                        color="cyan"
                        leftSection={<IconDownload size={16} />}
                        loading={snapshotStarting}
                        disabled={
                          snapshotStarting ||
                          stoppingSnapshot ||
                          !snapshotDownloadReady ||
                          ((snapshotProgress?.ready === true ||
                            workload?.status === 'snapshot_complete' ||
                            workload?.status === 'snapshot_running' ||
                            snapshotProgress?.phase === 'download') &&
                            !snapshotIdleAfterStop)
                        }
                        onClick={() => void startPanelSnapshotDownload()}
                      >
                        Download
                      </Button>
                    ) : null}
                    <Button
                      color="teal"
                      rightSection={<IconArrowRight size={16} />}
                      disabled={
                        snapshotViaNode
                          ? !snapshotDownloadReady
                          : !(snapshotProgress?.ready || workload?.status === 'snapshot_complete') ||
                            snapshotIdleAfterStop
                      }
                      onClick={() => void continueFromSnapshot()}
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
