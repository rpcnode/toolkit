import {
  ActionIcon,
  Alert,
  Button,
  Code,
  Group,
  Modal,
  Stack,
  Stepper,
  Text,
  TextInput,
  PasswordInput,
  Tooltip,
} from '@mantine/core'
import { IconCheck, IconCopy, IconServer } from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, apiBase } from '../api'
import { copyText } from '../lib/copyText'
import { DiscoverNodesPanel } from './DiscoverNodesPanel'
import { blockProps } from '../lib/blockId'

const DEFAULT_AGENT_PORT = 48990

function agentInstallOneLiner(origin: string): string {
  const base = origin.replace(/\/+$/, '')
  const jar = `${base}/install/binaries/rpcnode-agent.jar`
  return `curl -fsSL -o rpcnode-agent.jar ${jar} && sudo java -jar rpcnode-agent.jar install`
}

type Props = {
  opened: boolean
  onClose: () => void
  /** Called after a server is registered successfully. */
  onAdded?: () => void
}

/** rpcnode-agent HTTP API. Not Go tip :38990, not TRON public :39090. */
export { DEFAULT_AGENT_PORT }

function buildAgentURL(hostOrURL: string): string {
  const raw = hostOrURL.trim().replace(/\/+$/, '')
  if (!raw) return ''
  if (/^https?:\/\//i.test(raw)) return raw
  const bracket = raw.match(/^\[([^\]]+)\](?::(\d+))?$/)
  if (bracket) {
    return `http://[${bracket[1]}]:${bracket[2] || DEFAULT_AGENT_PORT}`
  }
  const colon = raw.lastIndexOf(':')
  if (colon > 0 && /^\d+$/.test(raw.slice(colon + 1))) {
    return `http://${raw}`
  }
  return `http://${raw}:${DEFAULT_AGENT_PORT}`
}

export function AddServerModal({ opened, onClose, onAdded }: Props) {
  const [active, setActive] = useState(0)
  const [panelUrl, setPanelUrl] = useState(() => apiBase())
  const [installCmd, setInstallCmd] = useState(() => agentInstallOneLiner(apiBase() || 'http://127.0.0.1:8093'))
  const [installCopied, setInstallCopied] = useState(false)
  const installCopiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(
    () => () => {
      if (installCopiedTimer.current) clearTimeout(installCopiedTimer.current)
    },
    [],
  )

  const [host, setHost] = useState('')
  const [agentKey, setAgentKey] = useState('')
  const [name, setName] = useState('')

  const [probing, setProbing] = useState(false)
  const [saving, setSaving] = useState(false)
  const [verified, setVerified] = useState(false)
  const [verifiedURL, setVerifiedURL] = useState('')
  const [probeError, setProbeError] = useState<string | null>(null)
  const [osPretty, setOsPretty] = useState('')
  const [os, setOS] = useState('')
  const [arch, setArch] = useState('')
  const [agentNetwork, setAgentNetwork] = useState('')
  const [probeWarning, setProbeWarning] = useState<string | null>(null)
  const [createdServerId, setCreatedServerId] = useState<string | null>(null)

  const agentURL = useMemo(() => buildAgentURL(host), [host])

  const refreshMeta = useCallback(async () => {
    try {
      const settings = await api.panelSettings().catch(() => null)
      const origin = (
        settings?.install_origin ||
        apiBase() ||
        (typeof window !== 'undefined' ? window.location.origin : '')
      ).replace(/\/+$/, '')
      if (origin) setPanelUrl(origin)
      if (settings?.curl?.trim()) {
        setInstallCmd(settings.curl.trim())
      } else if (origin) {
        setInstallCmd(agentInstallOneLiner(origin))
      }
    } catch {
      /* keep apiBase() */
    }
  }, [])

  const wasOpen = useRef(false)
  useEffect(() => {
    if (opened && !wasOpen.current) {
      setActive(0)
      setHost('')
      setAgentKey('')
      setName('')
      setVerified(false)
      setVerifiedURL('')
      setProbeError(null)
      setOsPretty('')
      setOS('')
      setArch('')
      setAgentNetwork('')
      setProbeWarning(null)
      setCreatedServerId(null)
      void refreshMeta()
    }
    wasOpen.current = opened
  }, [opened, refreshMeta])

  // Re-check when host or key changes.
  useEffect(() => {
    setVerified(false)
    setVerifiedURL('')
    setProbeError(null)
    setOsPretty('')
    setOS('')
    setArch('')
    setAgentNetwork('')
    setProbeWarning(null)
  }, [host, agentKey])

  const installOneLiner = installCmd

  function copyInstallCommand() {
    void copyText(installOneLiner)
      .then(() => {
        setInstallCopied(true)
        notifications.show({ color: 'teal', message: 'Install command copied', autoClose: 1500 })
        if (installCopiedTimer.current) clearTimeout(installCopiedTimer.current)
        installCopiedTimer.current = setTimeout(() => setInstallCopied(false), 1500)
      })
      .catch(() => {
        notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
      })
  }

  async function checkConnection() {
    if (!agentURL) {
      notifications.show({ color: 'yellow', message: 'Enter host IP or agent URL' })
      return
    }
    if (!agentKey.trim()) {
      notifications.show({ color: 'yellow', message: 'Paste AGENT_API_TOKEN from the installer' })
      return
    }

    setProbing(true)
    setProbeError(null)
    setVerified(false)
    setVerifiedURL('')
    try {
      const res = await api.registryProbe({
        agent_url: agentURL,
        agent_key: agentKey.trim(),
      })
      setVerifiedURL(agentURL)
      setOS(res.os || '')
      setArch(res.arch || '')
      setOsPretty(res.os_pretty || [res.os, res.arch].filter(Boolean).join('/') || 'ok')
      setAgentNetwork(res.agent_network || res.network || '')
      setProbeWarning(res.warning || null)
      setVerified(true)
      notifications.show({
        color: res.network_mismatch ? 'yellow' : 'teal',
        message: res.warning
          ? res.warning
          : `Connected ${agentURL}${res.os_pretty ? ` · ${res.os_pretty}` : ''}${
              res.agent_network ? ` · network=${res.agent_network}` : ''
            }`,
      })
      setActive(2)
    } catch (e) {
      const msg = String((e as Error).message || e)
      setProbeError(msg)
      notifications.show({ color: 'red', message: msg })
    } finally {
      setProbing(false)
    }
  }

  async function addServer() {
    const url = verifiedURL || agentURL
    if (!url || !agentKey.trim()) {
      notifications.show({ color: 'yellow', message: 'Enter host and agent secret' })
      return
    }
    setSaving(true)
    try {
      // Do not send id — panel allocates a unique server id (or updates by agent_url).
      // Never rely on network/env as identity (that used to overwrite tron-mainnet).
      const res = await api.registryUpsert({
        name: name.trim() || undefined,
        agent_url: url,
        agent_key: agentKey.trim(),
        os: os || undefined,
        arch: arch || undefined,
        os_pretty: osPretty || undefined,
        network: agentNetwork || undefined,
        panel_url: panelUrl || apiBase() || (typeof window !== 'undefined' ? window.location.origin : undefined),
      })
      if (res.warning || res.network_mismatch) {
        notifications.show({ color: 'yellow', message: res.warning || 'Agent network mismatch' })
      } else {
        notifications.show({
          color: 'teal',
          message: `Server added: ${res.item?.name || res.item?.id || agentURL}${
            res.agent_network ? ` · agent network=${res.agent_network}` : ''
          }`,
        })
      }
      onAdded?.()
      if (res.item?.id) {
        // A host that already runs fullnodes (re-added server, or agent
        // installed by hand first) — offer to import them instead of
        // making the operator re-type every network/env.
        setCreatedServerId(res.item.id)
        setActive(3)
      } else {
        onClose()
      }
    } catch (e) {
      notifications.show({ color: 'red', message: String((e as Error).message || e) })
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      {...blockProps('modal.add-server')}
      opened={opened}
      onClose={onClose}
      title={
        <Group gap="xs">
          <IconServer size={20} stroke={1.5} aria-hidden />
          <Text fw={600}>Add server</Text>
        </Group>
      }
      size="xl"
      centered
      closeOnClickOutside={false}
    >
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          Install the host agent, check that the panel can reach it, then add the server. Add asks the
          agent to GET the panel <Code>/healthz</Code> so we know metrics can flow back.
        </Text>

        <Stepper active={active} onStepClick={setActive} allowNextStepsSelect size="sm" color="teal">
          <Stepper.Step label="Install" description="on host" />
          <Stepper.Step label="Connect" description="panel → agent" />
          <Stepper.Step label="Add" description="agent → panel" />
          <Stepper.Step label="Existing nodes" description="optional" />
        </Stepper>

        {active === 0 && (
          <Stack gap="sm" {...blockProps('modal.add-server.step.install')}>
            <Text size="sm">
              On the host, download the agent jar from this panel and run install. It copies itself under{' '}
              <Code>/opt/rpcnode</Code>, starts systemd, and prints Agent URL + key at the end.
            </Text>
            <Group gap="xs" align="flex-start" wrap="nowrap">
              <Code block className="mono" style={{ flex: 1, minWidth: 0 }}>
                {installOneLiner}
              </Code>
              <Tooltip label={installCopied ? 'Copied' : 'Copy install command'}>
                <ActionIcon
                  size="lg"
                  variant="light"
                  color={installCopied ? 'teal' : 'gray'}
                  aria-label="Copy install command"
                  onClick={copyInstallCommand}
                >
                  {installCopied ? <IconCheck size={16} /> : <IconCopy size={16} />}
                </ActionIcon>
              </Tooltip>
            </Group>
            <Alert color="gray" variant="light">
              Copy <Code>Agent URL</Code> and <Code>Agent key</Code> from the install output into the
              next step. Network/env are chosen later when you add a node.
            </Alert>
            <Group justify="flex-end">
              <Button color="teal" onClick={() => setActive(1)}>
                Continue — enter IP &amp; secret
              </Button>
            </Group>
          </Stack>
        )}

        {active === 1 && (
          <Stack gap="sm" {...blockProps('modal.add-server.step.connect')}>
            <TextInput
              label="Host IP or agent URL"
              description={`As written — no port scan. Bare IP uses :${DEFAULT_AGENT_PORT}.`}
              placeholder={`192.168.0.59:${DEFAULT_AGENT_PORT}`}
              value={host}
              onChange={(e) => setHost(e.currentTarget.value)}
              required
            />
            {agentURL ? (
              <Text size="xs" c="dimmed">
                Probe: <Code>{agentURL}</Code>
              </Text>
            ) : null}
            <PasswordInput
              label="Agent secret"
              description="AGENT_API_TOKEN from the installer output"
              placeholder="paste token"
              value={agentKey}
              onChange={(e) => setAgentKey(e.currentTarget.value)}
              required
            />
            <TextInput
              label="Name"
              description="Optional display name"
              placeholder="edge-1"
              value={name}
              onChange={(e) => setName(e.currentTarget.value)}
            />
            {probeError && (
              <Alert color="red" title="Check failed">
                {probeError}
              </Alert>
            )}
            <Group justify="space-between">
              <Button variant="default" onClick={() => setActive(0)}>
                Back
              </Button>
              <Button color="teal" loading={probing} onClick={() => void checkConnection()}>
                Check connection
              </Button>
            </Group>
          </Stack>
        )}

        {active === 2 && (
          <Stack gap="sm" {...blockProps('modal.add-server.step.add')}>
            {verified ? (
              <Alert color="teal" icon={<IconCheck size={16} />} title="Connection OK">
                Agent reached at <Code>{verifiedURL || agentURL}</Code>
                {osPretty ? (
                  <>
                    {' '}
                    · OS <Code>{osPretty}</Code>
                  </>
                ) : null}
                {agentNetwork ? (
                  <>
                    {' '}
                    · agent network=<Code>{agentNetwork}</Code>
                  </>
                ) : null}
                .
              </Alert>
            ) : probeError ? (
              <Alert color="red" title="Check failed">
                {probeError}
              </Alert>
            ) : (
              <Alert color="yellow" title="Not checked">
                Connection was not verified. You can still add the server.
              </Alert>
            )}
            {probeWarning && (
              <Alert color="yellow" title="Network warning">
                {probeWarning}
              </Alert>
            )}
            <Alert color="cyan" variant="light" title="Agent → panel">
              <Text size="sm">
                Add tells the agent to GET <Code>{panelUrl || '—'}/healthz</Code>. That URL must be
                reachable from the host.
              </Text>
            </Alert>
            {/^(https?:\/\/)?(127\.0\.0\.1|localhost)\b/i.test(panelUrl.trim()) ? (
              <Alert color="yellow" title="Local origin">
                <Code>{panelUrl}</Code> is this machine. A remote agent cannot reach it — set install
                origin in Settings to a LAN or public URL first.
              </Alert>
            ) : null}
            <TextInput
              label="Name"
              description="Optional display name"
              placeholder="edge-1"
              value={name}
              onChange={(e) => setName(e.currentTarget.value)}
            />
            <Text size="xs" c="dimmed">
              Agent URL: <Code>{verifiedURL || agentURL || '—'}</Code>
            </Text>
            <Group justify="space-between">
              <Button variant="default" onClick={() => setActive(1)}>
                Back
              </Button>
              <Button
                color="teal"
                leftSection={<IconCheck size={16} />}
                loading={saving}
                disabled={!agentKey.trim() || !(verifiedURL || agentURL)}
                onClick={() => void addServer()}
              >
                {saving ? 'Agent pinging panel…' : 'Add server'}
              </Button>
            </Group>
          </Stack>
        )}

        {active === 3 && createdServerId && (
          <DiscoverNodesPanel
            serverId={createdServerId}
            onImported={onAdded}
            onDone={onClose}
            doneLabel="Done"
          />
        )}
      </Stack>
    </Modal>
  )
}

/** @deprecated alias — menu /install still uses this name */
export { AddServerModal as InstallAgentModal }
