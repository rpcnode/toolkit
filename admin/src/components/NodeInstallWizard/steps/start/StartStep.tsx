import {
  Alert,
  Badge,
  Box,
  Button,
  Code,
  Group,
  Loader,
  Stack,
  Switch,
  Text,
  TextInput,
  Title,
} from '@mantine/core'
import { IconAlertTriangle, IconPlayerPlay, IconRefresh } from '@tabler/icons-react'
import { blockProps } from '../../../../lib/blockId'
import { isSolanaNetwork } from '../../../../lib/network'
import { InstallOptionsPicker } from '../../../InstallOptionsPicker'
import { WizardStepHelp } from '../../WizardStepHelp'
import {
  bindingForCatalogPortRole,
  formatSolanaBuildPendingMessage,
  portConfigInstallOptionKey,
} from '../../utils'
import { useWizard, type WizardApi } from '../../wizardContext'


export function StartStep() {
  return <View {...useWizard()} />
}

function View({
    allowSnap,
    networkId,
    wantsInstallOptions,
    installGroups,
    nodeTypeOptionGroups,
    snapshotOptionGroups,
    installOptions,
    setInstallOptions,
    clientConfig,
    clientConfigRows,
    optionalPortBindings,
    hostSysctl,
    hostSysctlError,
    hostSysctlBelowRecommended,
    loadHostSysctl,
    applyRecommendedSysctl,
    sysctlKeyByOption,
    startBuildPending,
    buildLogLines,
    buildLogPath,
    buildLogScroller,
    startApplyError,
    startSaving,
    continueFromStart,
    testConnectBusy,
    testConnectConfig,
    testConnectResult,
    l1ParentChoices,
    l1ParentPickHelp,
    l1ParentLoading,
    applyL1ParentChoice,
    wantsL1ParentPicker,
    goBackToClientsOrEarlier,
    setUiStep,
    agentAckedStep,
    manualBackToClients,
    manualBackToDisks,
    manualBackToNodeType,
}: WizardApi) {
  return (
    <>
          
<Box {...blockProps('node.detail.wizard.step.start')}>
              <Stack gap="md">
              <Group justify="space-between">
                <Title order={3}>Client config</Title>
                <Badge color="gray" variant="light">
                  review
                </Badge>
              </Group>
              <Text c="dimmed" size="sm">
                Start downloads client files into the node dir, patches config (when the network uses a
                conf template), launches the chain process on the host, then sets status to sync.
                Height is reported every 10s.
              </Text>

              {isSolanaNetwork(networkId) ? (
                <Alert
                  color="yellow"
                  variant="light"
                  icon={<IconAlertTriangle size={16} />}
                  title="Agave validator build — two-step Start"
                >
                  <Text size="sm">
                    Anza no longer ships <Code>agave-validator</Code> in the release tarball. The first
                    Start only kicks off a background host build (OS packages + rustup + cargo — often
                    tens of minutes). The systemd unit is created on the next Start, after{' '}
                    <Code>bin/agave-validator</Code> appears under the node dir. Watch{' '}
                    <Code>.toolkit/agave-build.log</Code> on the host; when the binary exists, press
                    Start again.
                  </Text>
                </Alert>
              ) : null}

              {isSolanaNetwork(networkId) && (hostSysctl || hostSysctlError) ? (
                <Alert
                  color={hostSysctlBelowRecommended ? 'orange' : hostSysctlError ? 'yellow' : 'gray'}
                  variant="light"
                  icon={<IconAlertTriangle size={16} />}
                  title="Host sysctl (Agave UDP / mmap)"
                >
                  <Stack gap={6}>
                    {hostSysctlError ? (
                      <Text size="sm">{hostSysctlError}</Text>
                    ) : (
                      <Text size="sm">
                        {hostSysctlBelowRecommended
                          ? 'Host values are below Anza recommendations — Start applies the editable sysctl fields below (writes /etc/sysctl.d/21-solana.conf as root).'
                          : 'Host already meets recommended floors. You can still edit values below before Start.'}
                      </Text>
                    )}
                    <Group gap="xs">
                      <Button
                        size="xs"
                        variant="light"
                        disabled={!hostSysctl}
                        onClick={() => applyRecommendedSysctl()}
                      >
                        Use recommended
                      </Button>
                      <Button
                        size="xs"
                        variant="default"
                        leftSection={<IconRefresh size={14} />}
                        onClick={() => void loadHostSysctl()}
                      >
                        Refresh host
                      </Button>
                    </Group>
                  </Stack>
                </Alert>
              ) : null}

              {wantsInstallOptions && snapshotOptionGroups.length === 0 && nodeTypeOptionGroups.length === 0 ? (
                <InstallOptionsPicker
                  groups={installGroups}
                  value={installOptions}
                  onChange={setInstallOptions}
                />
              ) : null}

              {optionalPortBindings.length > 0 ? (
                <Stack gap="xs">
                  <Text size="sm" fw={600}>
                    Optional config ports
                  </Text>
                  <Text size="xs" c="dimmed">
                    P2P and RPC are always written into the config. Toggle extra catalog ports
                    (ZMQ, etc.) before Start — the host receives only what is enabled here.
                  </Text>
                  {optionalPortBindings.map((port: { role?: string; port?: number; label?: string; config?: string }) => {
                    const roleId = String(port.role || '').toLowerCase()
                    const opt = portConfigInstallOptionKey(roleId)
                    const binding = bindingForCatalogPortRole(clientConfig, roleId)
                    const on = (installOptions[opt] || '0').trim() === '1'
                    return (
                      <Group key={opt} justify="space-between" wrap="nowrap" gap="sm">
                        <Stack gap={2} style={{ minWidth: 0, flex: 1 }}>
                          <Text size="sm" fw={600}>
                            {binding?.description || port.label || roleId}
                          </Text>
                          <Text size="xs" c="dimmed" className="mono">
                            {binding?.path || roleId}
                            {port.port ? ` · ${port.port}` : ''}
                            {port.label ? ` · ${port.label}` : ''}
                          </Text>
                        </Stack>
                        <Switch
                          checked={on}
                          onChange={(e) => {
                            const checked = e.currentTarget.checked
                            setInstallOptions((prev: Record<string, string>) => ({
                              ...prev,
                              [opt]: checked ? '1' : '0',
                            }))
                          }}
                          aria-label={`Include ${binding?.path || roleId} in config`}
                        />
                      </Group>
                    )
                  })}
                </Stack>
              ) : null}

              <Stack gap="xs">
                <Text size="xs" c="dimmed">
                  Config preview
                  {clientConfig?.format ? ` · ${clientConfig.format}` : ''}
                  {clientConfig?.program ? ` · ${clientConfig.program}` : ''}
                  {' · '}
                  core fields always included
                </Text>
                {clientConfigRows.length > 0 ? (
                  <Stack gap={8}>
                    {clientConfigRows.map((row: { path: string; value: string; description: string; source: string; detail: string; editable: boolean; option?: string; portToggle?: string; alwaysOn?: boolean; testConnect?: { kind: string; label: string; help?: string } }) => {
                      const sysKey = row.option ? sysctlKeyByOption[row.option] : undefined
                      const hostNow =
                        sysKey && hostSysctl ? hostSysctl.current[sysKey] : undefined
                      const rec =
                        sysKey && hostSysctl ? hostSysctl.recommended[sysKey] : undefined
                      const hostHint =
                        sysKey && hostSysctl
                          ? `host now: ${hostNow ?? '—'} · recommended: ${rec ?? '—'}`
                          : ''
                      const connectKey = row.option || row.path
                      const connectState = testConnectResult[connectKey]
                      return (
                      <Stack key={`${row.path}:${row.source}:${row.option || ''}`} gap={6}>
                      <Group
                        justify="space-between"
                        align="flex-start"
                        wrap="nowrap"
                        gap="sm"
                      >
                        <Stack gap={2} style={{ minWidth: 0, flex: '0 1 48%' }}>
                          <Text size="sm" fw={600} className="mono">
                            {row.path}
                          </Text>
                          {row.description && row.description !== row.path ? (
                            <Text size="xs" c="dimmed">
                              {row.description}
                            </Text>
                          ) : null}
                          <Text size="xs" c="dimmed">
                            {row.source}
                            {row.detail ? ` · ${row.detail}` : ''}
                            {row.editable ? '' : ' · fixed'}
                          </Text>
                          {hostHint ? (
                            <Text
                              size="xs"
                              c={
                                typeof hostNow === 'number' &&
                                typeof rec === 'number' &&
                                hostNow < rec
                                  ? 'orange'
                                  : 'dimmed'
                              }
                              className="mono"
                            >
                              {hostHint}
                            </Text>
                          ) : null}
                        </Stack>
                        {row.editable && row.option ? (
                          <Stack gap={6} style={{ flex: '1 1 auto', minWidth: 120, maxWidth: 420 }}>
                            <TextInput
                              size="xs"
                              className="mono"
                              value={installOptions[row.option] ?? row.value}
                              onChange={(e) => {
                                const opt = row.option!
                                const v = e.currentTarget.value
                                setInstallOptions((prev: Record<string, string>) => ({ ...prev, [opt]: v }))
                              }}
                            />
                            {row.testConnect ? (
                              <Group gap="xs" wrap="wrap">
                                <Button
                                  size="xs"
                                  variant="light"
                                  loading={testConnectBusy === connectKey}
                                  disabled={testConnectBusy != null && testConnectBusy !== connectKey}
                                  onClick={() =>
                                    void testConnectConfig(
                                      row.testConnect!.kind,
                                      installOptions[row.option!] ?? row.value,
                                      connectKey,
                                    )
                                  }
                                >
                                  {row.testConnect.label || 'Test connect'}
                                </Button>
                                {row.testConnect.help ? (
                                  <WizardStepHelp
                                    title={`${row.path} — test connect`}
                                    text={row.testConnect.help}
                                  />
                                ) : null}
                              </Group>
                            ) : null}
                          </Stack>
                        ) : (
                          <Code
                            className="mono"
                            style={{
                              flex: '1 1 auto',
                              minWidth: 0,
                              whiteSpace: 'pre-wrap',
                              wordBreak: 'break-all',
                              fontSize: 12,
                            }}
                          >
                            {row.value}
                          </Code>
                        )}
                      </Group>
                      {connectState ? (
                        <Text size="xs" c={connectState.ok ? 'teal' : 'red'} className="mono">
                          {connectState.ok ? 'ok' : 'fail'} · {connectState.detail}
                        </Text>
                      ) : null}
                      </Stack>
                      )
                    })}
                  </Stack>
                ) : (
                  <Alert color="yellow" variant="light">
                    No clientConfig bindings for this network — check chains/
                    {networkId || '<id>'}/network.yml (restart the panel after editing).
                  </Alert>
                )}
              </Stack>

              {wantsL1ParentPicker ? (
                <Stack gap="xs">
                  <Group justify="space-between" align="center">
                    <Text size="sm" fw={600}>
                      L1 parent (Ethereum)
                    </Text>
                    {l1ParentLoading ? <Loader size="xs" /> : null}
                  </Group>
                  {l1ParentPickHelp ? (
                    <Text size="xs" c="dimmed" style={{ whiteSpace: 'pre-wrap' }}>
                      {l1ParentPickHelp}
                    </Text>
                  ) : null}
                  <Group gap="xs" wrap="wrap">
                    {l1ParentChoices.map((c: {
                      id: string
                      kind: string
                      label: string
                      rpc: string
                      beacon: string
                      same_host?: boolean
                    }) => {
                      const selected =
                        (installOptions.l1_rpc || '').trim() === c.rpc &&
                        (installOptions.l1_beacon || '').trim() === c.beacon
                      return (
                        <Button
                          key={c.id}
                          size="xs"
                          variant={selected ? 'filled' : 'light'}
                          color={c.kind === 'public' ? 'teal' : c.same_host ? 'blue' : 'gray'}
                          onClick={() => applyL1ParentChoice(c)}
                        >
                          {c.label}
                        </Button>
                      )
                    })}
                    {!l1ParentLoading && l1ParentChoices.length === 0 ? (
                      <Text size="xs" c="dimmed">
                        No ethereum publicTip URL and no active ethereum nodes for this L1 env.
                      </Text>
                    ) : null}
                  </Group>
                </Stack>
              ) : null}

              <Group justify="space-between" wrap="wrap" mt="md">
                <Button
                  variant="default"
                  disabled={startSaving}
                  onClick={() => {
                    if (allowSnap) {
                      setUiStep('snapshot')
                      agentAckedStep.current = 'snapshot'
                      manualBackToDisks.current = false
                      manualBackToNodeType.current = false
                      manualBackToClients.current = false
                    } else {
                      goBackToClientsOrEarlier()
                    }
                  }}
                >
                  Back
                </Button>
                <Button
                  color="teal"
                  rightSection={<IconPlayerPlay size={16} />}
                  loading={startSaving}
                  disabled={startSaving || clientConfigRows.length === 0}
                  onClick={() => void continueFromStart()}
                >
                  Start
                </Button>
              </Group>

              {startBuildPending && (
                <Alert
                  color="yellow"
                  variant="light"
                  icon={<IconAlertTriangle size={16} />}
                  title="Building agave-validator — wait, then Start again"
                >
                  <Stack gap={6}>
                    <Text size="sm">
                      Anza does not ship the validator binary in the release tarball. The host is
                      compiling it in the background. The systemd unit is created on the next Start
                      after the binary appears.
                    </Text>
                    <Text size="sm" className="mono" style={{ overflowWrap: 'anywhere' }}>
                      {formatSolanaBuildPendingMessage(startBuildPending)}
                    </Text>
                  </Stack>
                </Alert>
              )}

              {isSolanaNetwork(networkId) ? (
                <Stack gap="xs">
                  <Group justify="space-between" wrap="nowrap">
                    <Text size="sm" fw={600}>
                      Agave build log
                    </Text>
                    <Text size="xs" c="dimmed" className="mono" style={{ overflowWrap: 'anywhere' }}>
                      {buildLogPath || '.toolkit/agave-build.log'}
                    </Text>
                  </Group>
                  <Box
                    ref={buildLogScroller}
                    style={{
                      maxHeight: 280,
                      overflow: 'auto',
                      borderRadius: 8,
                      border: '1px solid var(--mantine-color-dark-4)',
                      background: 'var(--mantine-color-dark-7)',
                      padding: 8,
                    }}
                  >
                    {buildLogLines.length > 0 ? (
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
                        {buildLogLines.join('\n')}
                      </Code>
                    ) : (
                      <Text size="sm" c="dimmed">
                        {startBuildPending
                          ? 'Waiting for host build log…'
                          : 'Press Start to begin (first Start may only kick off the Agave build).'}
                      </Text>
                    )}
                  </Box>
                </Stack>
              ) : null}

              {startApplyError && (
                <Alert color="red" title="Could not start node">
                  <Text size="sm" style={{ overflowWrap: 'anywhere' }}>
                    {startApplyError}
                  </Text>
                </Alert>
              )}
              </Stack>
            </Box>
    
    </>
  )
}
