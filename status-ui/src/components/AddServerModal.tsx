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
  NumberInput,
  Tooltip,
} from '@mantine/core'
import { IconCheck, IconCopy, IconServer } from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../api'
import { copyText } from '../lib/copyText'

const DEFAULT_INSTALL = 'https://rpcnode.dev/install/agent.sh'

type Props = {
  opened: boolean
  onClose: () => void
  /** Called after a server is registered successfully. */
  onAdded?: () => void
}

function buildAgentURL(hostOrURL: string, port: number): string {
  const raw = hostOrURL.trim().replace(/\/+$/, '')
  if (!raw) return ''
  if (/^https?:\/\//i.test(raw)) return raw
  const host = raw.replace(/^\[|\]$/g, '')
  return `http://${host}:${port || 39090}`
}

/** Preferred external agent / RPC proxy port (installer scans if busy). */
export const DEFAULT_AGENT_PORT = 39090

/** Same shortlist the installer tries before range-scan. */
const AGENT_PORT_CANDIDATES = [
  39090, 39091, 39092, 39190, 39290, 39390, 39391, 39392, 39443, 47890, 48443, 48765,
]

export function AddServerModal({ opened, onClose, onAdded }: Props) {
  const [active, setActive] = useState(0)
  const [downloadURL, setDownloadURL] = useState(DEFAULT_INSTALL)
  const [installCopied, setInstallCopied] = useState(false)
  const installCopiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(
    () => () => {
      if (installCopiedTimer.current) clearTimeout(installCopiedTimer.current)
    },
    [],
  )

  const [host, setHost] = useState('')
  const [port, setPort] = useState<number | string>(DEFAULT_AGENT_PORT)
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

  const agentURL = useMemo(
    () =>
      buildAgentURL(
        host,
        typeof port === 'number' ? port : Number(port) || DEFAULT_AGENT_PORT,
      ),
    [host, port],
  )

  function portsToProbe(): string[] {
    const raw = host.trim().replace(/\/+$/, '')
    if (!raw) return []
    if (/^https?:\/\//i.test(raw)) return [raw]
    const preferred = typeof port === 'number' ? port : Number(port) || DEFAULT_AGENT_PORT
    const ports = [preferred, ...AGENT_PORT_CANDIDATES]
    const seen = new Set<number>()
    const urls: string[] = []
    for (const p of ports) {
      if (!Number.isFinite(p) || seen.has(p)) continue
      seen.add(p)
      urls.push(buildAgentURL(raw, p))
    }
    return urls
  }

  const refreshMeta = useCallback(async () => {
    try {
      const [cat, auth] = await Promise.all([
        api.devCatalog().catch(() => null),
        api.authStatus().catch(() => null),
      ])
      if (auth?.agent_download_url) setDownloadURL(auth.agent_download_url)
      else if (cat?.auth?.agent_download_url) setDownloadURL(cat.auth.agent_download_url)
    } catch {
      /* offline ok */
    }
  }, [])

  useEffect(() => {
    if (!opened) return
    setActive(0)
    setHost('')
    setPort(DEFAULT_AGENT_PORT)
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
    void refreshMeta()
  }, [opened, refreshMeta])

  // Re-check when host or key changes. Do not reset on port — a successful
  // probe updates the port (e.g. 39090 → 47890) and that used to wipe verified.
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

  const installOneLiner = `curl -fsSL "${downloadURL}" | sudo bash`

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
    const urls = portsToProbe()
    if (urls.length === 0) {
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
    const errors: string[] = []
    try {
      for (const url of urls) {
        try {
          const res = await api.registryProbe({
            agent_url: url,
            agent_key: agentKey.trim(),
          })
          setVerifiedURL(url)
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
              : `Connected ${url}${res.os_pretty ? ` · ${res.os_pretty}` : ''}${
                  res.agent_network ? ` · network=${res.agent_network}` : ''
                }`,
          })
          setActive(2)
          return
        } catch (e) {
          errors.push(`${url}: ${String((e as Error).message || e)}`)
        }
      }
      const msg =
        urls.length > 1
          ? `No agent on scanned ports. Last: ${errors[errors.length - 1] || 'failed'}`
          : errors[0] || 'Check failed'
      setProbeError(msg)
      notifications.show({ color: 'red', message: msg })
      setActive(2)
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
      onClose()
    } catch (e) {
      notifications.show({ color: 'red', message: String((e as Error).message || e) })
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={
        <Group gap="xs">
          <IconServer size={20} stroke={1.5} aria-hidden />
          <Text fw={600}>Add server</Text>
        </Group>
      }
      size="lg"
      centered
      closeOnClickOutside={false}
    >
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          Install the host agent, then enter IP and the agent secret. The panel checks the connection
          before registering the server.
        </Text>

        <Stepper active={active} onStepClick={setActive} allowNextStepsSelect size="sm" color="teal">
          <Stepper.Step label="Install" description="on host" />
          <Stepper.Step label="Connect" description="IP + secret" />
          <Stepper.Step label="Add" description="register" />
        </Stepper>

        {active === 0 && (
          <Stack gap="sm">
            <Text size="sm">
              Run on the blockchain server (Linux amd64/arm64). The installer prints Agent URL + key
              and systemd status at the end — nothing else to run by hand.
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
          <Stack gap="sm">
            <TextInput
              label="Host IP or agent URL"
              description="IP → http://IP:port. Or paste a full URL."
              placeholder="203.0.113.10"
              value={host}
              onChange={(e) => setHost(e.currentTarget.value)}
              required
            />
            <NumberInput
              label="External agent port"
              description={`Preferred ${DEFAULT_AGENT_PORT}. Check also scans nearby free ports if busy. Ignored if full URL pasted.`}
              value={port}
              onChange={setPort}
              min={1}
              max={65535}
              allowDecimal={false}
              disabled={/^https?:\/\//i.test(host.trim())}
            />
            <Alert color="cyan" variant="light" title="Open this port on the host">
              <Text size="sm">
                Control-plane agent API must be reachable from the panel. In ufw / security group allow
                TCP{' '}
                <Code>
                  {typeof port === 'number' ? port : Number(port) || DEFAULT_AGENT_PORT}
                </Code>
                . Later, the node setup wizard will show RPC/P2P ports for that workload.
              </Text>
            </Alert>
            {agentURL ? (
              <Text size="xs" c="dimmed">
                First probe: <Code>{agentURL}</Code>
                {!/^https?:\/\//i.test(host.trim()) ? ' · then auto-scan candidates' : ''}
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
          <Stack gap="sm">
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
                Add server
              </Button>
            </Group>
          </Stack>
        )}
      </Stack>
    </Modal>
  )
}

/** @deprecated alias — menu /install still uses this name */
export { AddServerModal as InstallAgentModal }
