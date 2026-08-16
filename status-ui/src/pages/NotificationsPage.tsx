import {
  Alert,
  Badge,
  Button,
  Card,
  Checkbox,
  Code,
  Group,
  NumberInput,
  PasswordInput,
  Stack,
  Switch,
  Text,
  TextInput,
  Title,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import {
  IconAlertTriangle,
  IconBell,
  IconCheck,
  IconSend,
  IconShieldLock,
} from '@tabler/icons-react'
import { useCallback, useEffect, useState } from 'react'
import { api, type NotifySettings } from '../api'
import { AppChrome, PageHint } from '../components/AppChrome'

export function NotificationsPage() {
  const [settings, setSettings] = useState<NotifySettings | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [verifying, setVerifying] = useState(false)
  const [botToken, setBotToken] = useState('')
  const [chatId, setChatId] = useState('')
  const [enabled, setEnabled] = useState(false)
  const [subs, setSubs] = useState<Record<string, boolean>>({})
  const [diskPct, setDiskPct] = useState<number | string>(90)
  const [cpuPct, setCpuPct] = useState<number | string>(90)
  const [latencyMs, setLatencyMs] = useState<number | string>(2000)
  const [errorRatePct, setErrorRatePct] = useState<number | string>(10)
  const [rpcRps, setRpcRps] = useState<number | string>(1000)
  const [nodeDownHoldSec, setNodeDownHoldSec] = useState<number | string>(45)
  const [nodeUpHoldSec, setNodeUpHoldSec] = useState<number | string>(20)
  const [verifyCode, setVerifyCode] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const st = await api.notifySettings()
      setSettings(st)
      setChatId(st.chat_id || '')
      setEnabled(!!st.enabled)
      setSubs({ ...(st.subscriptions || {}) })
      setDiskPct(st.thresholds?.disk_used_pct ?? 90)
      setCpuPct(st.thresholds?.cpu_high_pct ?? 90)
      setLatencyMs(st.thresholds?.rpc_latency_p95_ms ?? 2000)
      setErrorRatePct(st.thresholds?.rpc_error_rate_pct ?? 10)
      setRpcRps(st.thresholds?.rpc_rps ?? 1000)
      setNodeDownHoldSec(st.thresholds?.node_down_hold_sec ?? 45)
      setNodeUpHoldSec(st.thresholds?.node_up_hold_sec ?? 20)
      setBotToken('')
    } catch (e) {
      notifications.show({
        color: 'red',
        message: String((e as Error).message || e),
      })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  async function save() {
    setSaving(true)
    try {
      const body: Parameters<typeof api.notifySaveSettings>[0] = {
        chat_id: chatId.trim(),
        enabled,
        subscriptions: subs,
        thresholds: {
          disk_used_pct: Number(diskPct) || 90,
          cpu_high_pct: Number(cpuPct) || 90,
          rpc_latency_p95_ms: Number(latencyMs) || 2000,
          rpc_error_rate_pct: Number(errorRatePct) || 10,
          rpc_rps: Number(rpcRps) || 1000,
          node_down_hold_sec: Number(nodeDownHoldSec) || 45,
          node_up_hold_sec: Number(nodeUpHoldSec) || 20,
        },
      }
      if (botToken.trim()) body.bot_token = botToken.trim()
      const st = await api.notifySaveSettings(body)
      setSettings(st)
      setBotToken('')
      setChatId(st.chat_id || '')
      setEnabled(!!st.enabled)
      setSubs({ ...(st.subscriptions || {}) })
      setDiskPct(st.thresholds?.disk_used_pct ?? 90)
      setCpuPct(st.thresholds?.cpu_high_pct ?? 90)
      setLatencyMs(st.thresholds?.rpc_latency_p95_ms ?? 2000)
      setErrorRatePct(st.thresholds?.rpc_error_rate_pct ?? 10)
      setRpcRps(st.thresholds?.rpc_rps ?? 1000)
      setNodeDownHoldSec(st.thresholds?.node_down_hold_sec ?? 45)
      setNodeUpHoldSec(st.thresholds?.node_up_hold_sec ?? 20)
      notifications.show({ color: 'teal', message: 'Notification settings saved' })
    } catch (e) {
      notifications.show({
        color: 'red',
        message: String((e as Error).message || e),
      })
    } finally {
      setSaving(false)
    }
  }

  async function sendTest() {
    setTesting(true)
    try {
      // Persist chat/token first if dirty.
      if (botToken.trim() || chatId.trim() !== (settings?.chat_id || '')) {
        await api.notifySaveSettings({
          bot_token: botToken.trim() || undefined,
          chat_id: chatId.trim(),
          enabled,
          subscriptions: subs,
          thresholds: {
            disk_used_pct: Number(diskPct) || 90,
            cpu_high_pct: Number(cpuPct) || 90,
            rpc_latency_p95_ms: Number(latencyMs) || 2000,
            rpc_error_rate_pct: Number(errorRatePct) || 10,
            rpc_rps: Number(rpcRps) || 1000,
            node_down_hold_sec: Number(nodeDownHoldSec) || 45,
            node_up_hold_sec: Number(nodeUpHoldSec) || 20,
          },
        })
        setBotToken('')
      }
      const res = await api.notifyTest()
      notifications.show({
        color: 'teal',
        message: res.message || 'Test code sent — check Telegram',
      })
      await load()
    } catch (e) {
      notifications.show({
        color: 'red',
        message: String((e as Error).message || e),
      })
    } finally {
      setTesting(false)
    }
  }

  async function verify() {
    setVerifying(true)
    try {
      const st = await api.notifyVerify(verifyCode.trim())
      setSettings(st)
      setEnabled(!!st.enabled)
      setVerifyCode('')
      notifications.show({ color: 'teal', message: 'Channel verified — alerts enabled' })
    } catch (e) {
      notifications.show({
        color: 'red',
        message: String((e as Error).message || e),
      })
    } finally {
      setVerifying(false)
    }
  }

  const catalog = settings?.subscription_catalog || []

  return (
    <AppChrome
      title="Notifications"
      subtitle={
        <PageHint>
          Telegram alerts for client updates, install lifecycle, node down/up, agent updates, disk.
        </PageHint>
      }
    >
      <Stack gap="md" mt="md">
        <Card>
          <Stack gap="md">
            <Group justify="space-between" align="flex-start" wrap="wrap">
              <div>
                <Title order={4}>Telegram</Title>
                <Text size="sm" c="dimmed">
                  Bot token is encrypted at rest (AES-GCM). Key lives in{' '}
                  <Code>panel.notify.key</Code> next to the DB — not inside{' '}
                  <Code>panel.db</Code>.
                </Text>
              </div>
              <Group gap="xs">
                {settings?.verified ? (
                  <Badge color="teal" leftSection={<IconCheck size={12} />}>
                    Verified
                  </Badge>
                ) : (
                  <Badge color="yellow">Not verified</Badge>
                )}
                {settings?.has_token ? (
                  <Badge color="gray" variant="light">
                    Token {settings.token_masked || '••••'}
                  </Badge>
                ) : null}
              </Group>
            </Group>

            {settings?.has_token && settings.token_decrypt_ok === false ? (
              <Alert color="red" icon={<IconAlertTriangle size={16} />}>
                Cannot decrypt stored token (missing/rotated <Code>panel.notify.key</Code>). Re-enter
                the bot token and verify again.
              </Alert>
            ) : null}

            {settings?.last_error ? (
              <Alert color="orange" icon={<IconAlertTriangle size={16} />}>
                {settings.last_error}
              </Alert>
            ) : null}

            <PasswordInput
              label="Bot token"
              description={
                settings?.has_token
                  ? 'Leave blank to keep the current token'
                  : 'From @BotFather'
              }
              placeholder={settings?.token_masked || '123456:ABC…'}
              value={botToken}
              onChange={(e) => setBotToken(e.currentTarget.value)}
              disabled={loading}
            />
            <TextInput
              label="Chat ID"
              description="Numeric chat id or @channelusername (bot must be able to post)"
              placeholder="-100… or @mychannel"
              value={chatId}
              onChange={(e) => setChatId(e.currentTarget.value)}
              disabled={loading}
            />
            <Switch
              label="Enable alerts"
              description="Requires a successful Test → Verify first."
              checked={enabled}
              disabled={loading || !settings?.verified}
              onChange={(e) => setEnabled(e.currentTarget.checked)}
            />

            <Group>
              <Button
                leftSection={<IconShieldLock size={16} />}
                loading={saving}
                disabled={loading}
                onClick={() => void save()}
              >
                Save
              </Button>
              <Button
                variant="light"
                color="orange"
                leftSection={<IconSend size={16} />}
                loading={testing}
                disabled={loading || (!settings?.has_token && !botToken.trim()) || !chatId.trim()}
                onClick={() => void sendTest()}
              >
                Send test code
              </Button>
            </Group>

            <Group align="flex-end" wrap="wrap">
              <TextInput
                label="Verify code from Telegram"
                placeholder="6-digit code"
                value={verifyCode}
                onChange={(e) => setVerifyCode(e.currentTarget.value)}
                disabled={loading}
                style={{ flex: 1, minWidth: 180 }}
              />
              <Button
                color="teal"
                leftSection={<IconCheck size={16} />}
                loading={verifying}
                disabled={loading || verifyCode.trim().length < 4}
                onClick={() => void verify()}
              >
                Verify
              </Button>
            </Group>

            {settings?.key_source ? (
              <Text size="xs" c="dimmed">
                Encryption key source: <Code>{settings.key_source}</Code>
                {settings.key_path ? (
                  <>
                    {' '}
                    · <Code>{settings.key_path}</Code>
                  </>
                ) : null}
                . Backup DB <strong>and</strong> key (or set <Code>RPCNODE_NOTIFY_KEY</Code>).
              </Text>
            ) : null}
          </Stack>
        </Card>

        <Card>
          <Stack gap="sm">
            <Group gap="xs">
              <IconBell size={18} stroke={1.5} />
              <Title order={4}>Subscriptions</Title>
            </Group>
            <Text size="sm" c="dimmed">
              Choose which events the panel sends to Telegram after the channel is verified.
            </Text>
            {catalog.map((item) => (
              <Checkbox
                key={item.key}
                label={item.label}
                description={item.description}
                checked={!!subs[item.key]}
                disabled={loading}
                onChange={(e) =>
                  setSubs((prev) => ({ ...prev, [item.key]: e.currentTarget.checked }))
                }
              />
            ))}
            <Title order={5} mt="sm">
              Alert thresholds
            </Title>
            <Text size="sm" c="dimmed">
              Host: disk/CPU default <Code>90%</Code>. Fullnode Go RPC (public proxy): RPS default{' '}
              <Code>1000</Code>/s, p95 <Code>2000 ms</Code>, errors <Code>10%</Code>. Node down/up
              require a continuous hold (default <Code>45s</Code> / <Code>20s</Code>) so a single
              poll timeout does not flap Telegram.
            </Text>
            <Group grow align="flex-start">
              <NumberInput
                label="Disk used (%)"
                description="Disk low when used ≥ this"
                min={1}
                max={100}
                step={1}
                value={diskPct}
                onChange={setDiskPct}
                disabled={loading}
              />
              <NumberInput
                label="CPU (%)"
                description="CPU high when cpu_pct ≥ this (not load)"
                min={1}
                max={100}
                step={1}
                value={cpuPct}
                onChange={setCpuPct}
                disabled={loading}
              />
            </Group>
            <Text size="sm" fw={600} mt={4}>
              Node reachability
            </Text>
            <Group grow align="flex-start">
              <NumberInput
                label="Down hold (sec)"
                description="Continuous unreachable before node.down"
                min={5}
                max={3600}
                step={5}
                value={nodeDownHoldSec}
                onChange={setNodeDownHoldSec}
                disabled={loading}
              />
              <NumberInput
                label="Up hold (sec)"
                description="Continuous healthy before node.up"
                min={5}
                max={3600}
                step={5}
                value={nodeUpHoldSec}
                onChange={setNodeUpHoldSec}
                disabled={loading}
              />
            </Group>
            <Text size="sm" fw={600} mt={4}>
              Fullnode Go RPC
            </Text>
            <Group grow align="flex-start">
              <NumberInput
                label="Requests / sec (RPS)"
                description="rps_1m on public Go proxy ≥ this"
                min={1}
                max={1_000_000}
                step={100}
                value={rpcRps}
                onChange={setRpcRps}
                disabled={loading}
              />
              <NumberInput
                label="Latency p95 (ms)"
                description="Proxy p95 ≥ this"
                min={50}
                max={120000}
                step={100}
                value={latencyMs}
                onChange={setLatencyMs}
                disabled={loading}
              />
              <NumberInput
                label="Error rate (%)"
                description="5xx+upstream rate ≥ this"
                min={0.1}
                max={100}
                step={0.5}
                decimalScale={1}
                value={errorRatePct}
                onChange={setErrorRatePct}
                disabled={loading}
              />
            </Group>
            <Group>
              <Button
                variant="light"
                loading={saving}
                disabled={loading}
                onClick={() => void save()}
              >
                Save subscriptions
              </Button>
            </Group>
          </Stack>
        </Card>
      </Stack>
    </AppChrome>
  )
}
