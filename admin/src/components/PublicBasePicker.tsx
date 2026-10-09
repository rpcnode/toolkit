import {
  Button,
  Code,
  Group,
  Select,
  Stack,
  Text,
  TextInput,
  Tooltip,
} from '@mantine/core'
import { IconCheck, IconCopy } from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import { useEffect, useMemo, useState } from 'react'
import { api } from '../api'
import { copyText } from '../lib/copyText'
import type { StatusPayload } from '../types'

type Props = {
  status: StatusPayload
  rpcPort: number
  panelPort: number
  env: string
  onDone: () => void
}

function hostFromBase(url?: string): string {
  if (!url) return ''
  try {
    return new URL(url).hostname
  } catch {
    return ''
  }
}

export function PublicBasePicker({ status, rpcPort, panelPort, env, onDone }: Props) {
  const host = status.host || {}
  const candidates = useMemo(() => {
    const list = (host.ips || [])
      .map((x) => String(x.ip || '').trim())
      .filter(Boolean)
    const primary = String(host.primary_ip || '').trim()
    if (primary && !list.includes(primary)) list.unshift(primary)
    return list
  }, [host.ips, host.primary_ip])

  const currentHost = hostFromBase(status.connect?.base_url || String(status.instance?.public_base_url || ''))
  const defaultIp = candidates.includes(currentHost)
    ? currentHost
    : candidates[0] || currentHost || ''

  const [selected, setSelected] = useState<string | null>(defaultIp || null)
  const [manual, setManual] = useState('')
  const [busy, setBusy] = useState(false)
  const [touched, setTouched] = useState(false)

  useEffect(() => {
    if (touched) return
    if (defaultIp) setSelected(defaultIp)
  }, [defaultIp, touched])

  const ip = (manual.trim() || selected || '').trim()
  const rpcBase = ip ? `http://${ip}:${rpcPort}` : ''
  const panelBase = ip ? `http://${ip}:${panelPort}` : ''
  const panelStatus = panelBase ? `${panelBase}/status` : ''

  const snippet = ip
    ? `# in /etc/rpcnode/${env}/toolkit.env (or network profile env)\nRPCNODE_PUBLIC_BASE=${rpcBase}\nRPCNODE_PANEL_BASE=${panelBase}\nRPCNODE_PUBLIC_PORT=${rpcPort}\n# agent API port (panel_port): ${panelPort}\n# reload agent units after edit`
    : `# pick a host IP above\nRPCNODE_PUBLIC_BASE=http://<IP>:${rpcPort}\nRPCNODE_PANEL_BASE=http://<IP>:${panelPort}`

  const selectData = [
    ...candidates.map((c) => {
      const meta = (host.ips || []).find((x) => x.ip === c)
      const label = meta?.iface
        ? `${c}  (${meta.iface}${meta.primary ? ', primary' : ''})`
        : meta?.primary
          ? `${c}  (primary)`
          : c
      return { value: c, label }
    }),
    { value: '__manual__', label: 'Enter IP / hostname manually…' },
  ]

  const selectValue = manual || candidates.length === 0 ? '__manual__' : selected

  function copy(text: string, msg = 'Copied') {
    void copyText(text)
      .then(() => {
        notifications.show({ color: 'blue', message: msg })
      })
      .catch(() => {
        notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
      })
  }

  async function apply() {
    if (!ip) {
      notifications.show({ color: 'yellow', message: 'Select or enter a host IP first' })
      return
    }
    setBusy(true)
    try {
      const res = await api.publicBaseApply({ ip })
      notifications.show({
        color: 'teal',
        message: res.message || `Applied ${res.public_base || rpcBase}`,
      })
      if (res.env_error) {
        notifications.show({
          color: 'yellow',
          message: `toolkit.env not writable — override saved. Run: ${res.restart_hint || 'systemctl restart rpcnode-*-agent (or panel Update agent)'}`,
        })
      } else if (res.env_updated && res.restart_hint) {
        notifications.show({
          color: 'blue',
          message: `Reload agents to pick env: ${res.restart_hint}`,
        })
      }
      onDone()
    } catch (e) {
      notifications.show({ color: 'red', message: String((e as Error).message || e) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Stack gap="sm">
      <Text size="sm" c="dimmed">
        Choose the IP clients use for RPC. <code>RPCNODE_PUBLIC_BASE</code> uses the public RPC port
        ({rpcPort}); the Node Agent API stays on :{panelPort}
        {panelPort === 8093 ? ' when that is the agent listen port' : ''}.
      </Text>

      <Select
        label="Host IP"
        placeholder={candidates.length ? 'Detected addresses' : 'No LAN IPs detected — enter manually'}
        data={selectData}
        value={selectValue}
        onChange={(v) => {
          setTouched(true)
          if (v === '__manual__') {
            setSelected(null)
            if (!manual && defaultIp) setManual(defaultIp)
            return
          }
          setManual('')
          setSelected(v)
        }}
        searchable
        allowDeselect={false}
      />

      {(selectValue === '__manual__' || candidates.length === 0) && (
        <TextInput
          label="IP or hostname"
          placeholder="192.168.1.10"
          value={manual}
          onChange={(e) => {
            setTouched(true)
            setManual(e.currentTarget.value)
          }}
        />
      )}

      {ip && (
        <Stack gap={4}>
          <Text size="sm">
            RPC public base:{' '}
            <Code className="mono">{rpcBase}</Code>
          </Text>
          <Text size="sm" c="dimmed">
            Panel (separate):{' '}
            <Code className="mono">{panelStatus}</Code>
          </Text>
        </Stack>
      )}

      <Code block className="mono" style={{ whiteSpace: 'pre-wrap' }}>
        {snippet}
      </Code>

      <Group gap="sm">
        <Button
          color="teal"
          leftSection={<IconCheck size={16} />}
          loading={busy}
          disabled={!ip}
          onClick={() => void apply()}
        >
          Apply public base
        </Button>
        <Tooltip label="Copy env snippet">
          <Button
            size="sm"
            variant="default"
            leftSection={<IconCopy size={14} />}
            disabled={!ip}
            onClick={() => copy(snippet, 'Env snippet copied')}
          >
            Copy
          </Button>
        </Tooltip>
        {ip && (
          <Tooltip label="Copy reload hint">
            <Button
              size="sm"
              variant="light"
              color="cyan"
              onClick={() =>
                copy(
                  `# set RPCNODE_PUBLIC_BASE=${rpcBase} in toolkit.env for ${env}\n# then restart agent units (or Update agent from panel)`,
                  'Hint copied',
                )
              }
            >
              Copy reload hint
            </Button>
          </Tooltip>
        )}
      </Group>
    </Stack>
  )
}
